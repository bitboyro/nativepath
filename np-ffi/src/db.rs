//! C ABI for the NativePath database engines (`np_db_*`). Only PostgreSQL so far.
//!
//! Results are exposed as flat `#[repr(C)]` structs that Java reads in place, so reading a cell
//! costs no native call. Every function catches panics and reports them as errors.

use std::ffi::{CStr, CString};
use std::os::raw::c_char;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::time::Duration;
use np_db_postgres::{CancelToken, Cell, ChannelBinding, Config, Connection, DbError, ErrorKind, Param, QueryResult, SslMode};

pub struct PgConnHandle {
    inner: Option<Connection>,
}

/// One bind parameter: `len == -1` is SQL NULL. 24 bytes.
#[repr(C)]
pub struct WkDbParam {
    pub ptr: *const u8,
    pub len: i64,
    pub type_oid: u32,
    pub _pad: u32,
}

/// 24 bytes.
#[repr(C)]
pub struct WkDbColumn {
    pub name_ptr: *const u8,
    pub name_len: u64,
    pub type_oid: u32,
    pub table_oid: u32,
}

/// One statement result, pointing into memory owned by `WkDbResults`. 80 bytes.
#[repr(C)]
pub struct WkDbResult {
    pub columns: *const WkDbColumn,
    pub ncols: u64,
    pub cells: *const Cell,
    pub nrows: u64,
    pub data: *const u8,
    pub data_len: u64,
    pub rows_affected: i64,
    pub has_rows: u32,
    pub _pad: u32,
    pub tag_ptr: *const u8,
    pub tag_len: u64,
}

pub struct WkDbResults {
    // Kept alive for the raw pointers below; never touched after construction.
    _results: Vec<QueryResult>,
    _columns: Vec<Vec<WkDbColumn>>,
    raws: Vec<WkDbResult>,
}

/// 32 bytes. Strings are NUL-terminated; `detail` is null when the server sent none.
#[repr(C)]
pub struct WkDbError {
    pub kind: i32,
    pub _pad: i32,
    pub sqlstate: *mut c_char,
    pub message: *mut c_char,
    pub detail: *mut c_char,
}

fn cstring(s: &str) -> *mut c_char {
    CString::new(s.replace('\0', " ")).unwrap_or_default().into_raw()
}

unsafe fn set_error(out: *mut *mut WkDbError, e: DbError) {
    if out.is_null() {
        return;
    }
    *out = Box::into_raw(Box::new(WkDbError {
        kind: e.kind as i32,
        _pad: 0,
        sqlstate: cstring(&e.sqlstate),
        message: cstring(&e.message),
        detail: e.detail.as_deref().map(cstring).unwrap_or(std::ptr::null_mut()),
    }));
}

fn panic_error() -> DbError {
    DbError { kind: ErrorKind::Protocol, sqlstate: "XX000".into(), message: "internal error in the native driver".into(), detail: None }
}

unsafe fn str_arg<'a>(p: *const c_char) -> Result<&'a str, DbError> {
    if p.is_null() {
        return Ok("");
    }
    CStr::from_ptr(p).to_str().map_err(|_| DbError { kind: ErrorKind::Usage, sqlstate: "22021".into(), message: "argument is not valid UTF-8".into(), detail: None })
}

/// A byte slice from Java; a zero length may come with a null or dangling pointer.
unsafe fn bytes_arg<'a>(p: *const u8, len: usize) -> &'a [u8] {
    if len == 0 || p.is_null() {
        &[]
    } else {
        std::slice::from_raw_parts(p, len)
    }
}

unsafe fn guarded<T>(out_err: *mut *mut WkDbError, f: impl FnOnce() -> Result<T, DbError>) -> Option<T> {
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(Ok(v)) => Some(v),
        Ok(Err(e)) => {
            set_error(out_err, e);
            None
        }
        Err(_) => {
            set_error(out_err, panic_error());
            None
        }
    }
}

fn into_results(results: Vec<QueryResult>) -> *mut WkDbResults {
    let columns: Vec<Vec<WkDbColumn>> = results
        .iter()
        .map(|r| {
            r.columns
                .iter()
                .map(|c| WkDbColumn { name_ptr: c.name.as_ptr(), name_len: c.name.len() as u64, type_oid: c.type_oid, table_oid: c.table_oid })
                .collect()
        })
        .collect();
    let raws = results
        .iter()
        .zip(columns.iter())
        .map(|(r, cols)| WkDbResult {
            columns: cols.as_ptr(),
            ncols: cols.len() as u64,
            cells: r.cells.as_ptr(),
            nrows: r.nrows as u64,
            data: r.data.as_ptr(),
            data_len: r.data.len() as u64,
            rows_affected: r.rows_affected,
            has_rows: r.has_rows as u32,
            _pad: 0,
            tag_ptr: r.tag.as_ptr(),
            tag_len: r.tag.len() as u64,
        })
        .collect();
    Box::into_raw(Box::new(WkDbResults { _results: results, _columns: columns, raws }))
}

unsafe fn conn_mut<'a>(h: *mut PgConnHandle) -> Result<&'a mut Connection, DbError> {
    h.as_mut()
        .and_then(|h| h.inner.as_mut())
        .ok_or_else(|| DbError { kind: ErrorKind::Usage, sqlstate: "08003".into(), message: "connection is closed".into(), detail: None })
}

fn usage(message: &str) -> DbError {
    DbError { kind: ErrorKind::Usage, sqlstate: "08001".into(), message: message.into(), detail: None }
}

/// Connects and authenticates. `password` (may be null) is read in place; `password_len == -1`
/// means no password. `ssl_mode` and `channel_binding` use the values of `SslMode` and
/// `ChannelBinding`; `ssl_root_cert` is a PEM path or null. Returns null and sets `*out_err` on failure.
#[no_mangle]
pub unsafe extern "C" fn np_db_pg_connect(
    host: *const c_char,
    port: u16,
    user: *const c_char,
    database: *const c_char,
    application_name: *const c_char,
    password: *const u8,
    password_len: i64,
    connect_timeout_ms: u32,
    ssl_mode: i32,
    ssl_root_cert: *const c_char,
    channel_binding: i32,
    out_err: *mut *mut WkDbError,
) -> *mut PgConnHandle {
    guarded(out_err, || {
        let mut config = Config::new(str_arg(host)?, port, str_arg(user)?, str_arg(database)?);
        config.application_name = str_arg(application_name)?.to_string();
        config.connect_timeout = (connect_timeout_ms > 0).then(|| Duration::from_millis(connect_timeout_ms as u64));
        config.ssl_mode = SslMode::from_i32(ssl_mode).ok_or_else(|| usage("invalid sslmode"))?;
        config.channel_binding = ChannelBinding::from_i32(channel_binding).ok_or_else(|| usage("invalid channel_binding"))?;
        let root = str_arg(ssl_root_cert)?;
        config.ssl_root_cert = (!root.is_empty()).then(|| root.into());
        let pw = (!password.is_null() && password_len >= 0).then(|| std::slice::from_raw_parts(password, password_len as usize));
        let conn = Connection::connect(&config, pw)?;
        Ok(Box::into_raw(Box::new(PgConnHandle { inner: Some(conn) })))
    })
    .unwrap_or(std::ptr::null_mut())
}

/// Sends Terminate, closes the socket and frees the handle.
#[no_mangle]
pub unsafe extern "C" fn np_db_pg_close(h: *mut PgConnHandle) {
    if h.is_null() {
        return;
    }
    let _ = catch_unwind(AssertUnwindSafe(|| {
        let mut handle = Box::from_raw(h);
        if let Some(c) = handle.inner.take() {
            c.close();
        }
    }));
}

/// Runs one statement (extended protocol, text format). Returns null and sets `*out_err` on failure.
#[no_mangle]
pub unsafe extern "C" fn np_db_pg_execute(
    h: *mut PgConnHandle,
    sql: *const u8,
    sql_len: usize,
    params: *const WkDbParam,
    nparams: usize,
    out_err: *mut *mut WkDbError,
) -> *mut WkDbResults {
    guarded(out_err, || {
        let conn = conn_mut(h)?;
        let sql = bytes_arg(sql, sql_len);
        let raw = if nparams == 0 { &[][..] } else { std::slice::from_raw_parts(params, nparams) };
        let params: Vec<Param<'_>> = raw
            .iter()
            .map(|p| Param {
                value: (p.len >= 0).then(|| bytes_arg(p.ptr, p.len as usize)),
                type_oid: p.type_oid,
            })
            .collect();
        let result = conn.execute(sql, &params)?;
        Ok(into_results(vec![result]))
    })
    .unwrap_or(std::ptr::null_mut())
}

/// Runs SQL with the simple protocol (no parameters, several statements allowed).
#[no_mangle]
pub unsafe extern "C" fn np_db_pg_simple(h: *mut PgConnHandle, sql: *const u8, sql_len: usize, out_err: *mut *mut WkDbError) -> *mut WkDbResults {
    guarded(out_err, || {
        let conn = conn_mut(h)?;
        let results = conn.simple_query(bytes_arg(sql, sql_len))?;
        Ok(into_results(results))
    })
    .unwrap_or(std::ptr::null_mut())
}

/// Bit 0: the connection is encrypted. Bit 1: authentication used channel binding.
#[no_mangle]
pub unsafe extern "C" fn np_db_pg_security(h: *mut PgConnHandle) -> u32 {
    match h.as_ref().and_then(|h| h.inner.as_ref()) {
        Some(c) => c.is_tls() as u32 | ((c.is_channel_bound() as u32) << 1),
        None => 0,
    }
}

/// A cancel handle for the connection, independent of it: it stays valid after the connection
/// closes and may be used from any thread. Free with `np_db_pg_cancel_token_free`.
#[no_mangle]
pub unsafe extern "C" fn np_db_pg_cancel_token(h: *mut PgConnHandle) -> *mut CancelToken {
    match h.as_ref().and_then(|h| h.inner.as_ref()) {
        Some(c) => Box::into_raw(Box::new(c.cancel_token())),
        None => std::ptr::null_mut(),
    }
}

/// Asks the server to cancel the statement running on the token's connection. Blocks until the
/// server has taken the request. Returns null on success, or an error (free with `np_db_error_free`).
#[no_mangle]
pub unsafe extern "C" fn np_db_pg_cancel(t: *const CancelToken) -> *mut WkDbError {
    let mut err: *mut WkDbError = std::ptr::null_mut();
    guarded(&mut err, || match t.as_ref() {
        Some(t) => t.cancel(),
        None => Err(usage("no cancel token")),
    });
    err
}

#[no_mangle]
pub unsafe extern "C" fn np_db_pg_cancel_token_free(t: *mut CancelToken) {
    if !t.is_null() {
        drop(Box::from_raw(t));
    }
}

/// `I` idle, `T` in a transaction, `E` failed transaction, `X` broken or closed.
#[no_mangle]
pub unsafe extern "C" fn np_db_pg_tx_status(h: *mut PgConnHandle) -> u8 {
    match h.as_ref().and_then(|h| h.inner.as_ref()) {
        Some(c) if !c.is_broken() => c.tx_status(),
        _ => b'X',
    }
}

/// A server parameter such as `server_version`, or null. Free with `np_string_free`.
#[no_mangle]
pub unsafe extern "C" fn np_db_pg_parameter(h: *mut PgConnHandle, name: *const c_char) -> *mut c_char {
    let Some(c) = h.as_ref().and_then(|h| h.inner.as_ref()) else { return std::ptr::null_mut() };
    let Ok(name) = str_arg(name) else { return std::ptr::null_mut() };
    c.parameter(name).map(cstring).unwrap_or(std::ptr::null_mut())
}

/// Socket read/write timeout in milliseconds; 0 disables it. Returns false on failure.
#[no_mangle]
pub unsafe extern "C" fn np_db_pg_set_timeout(h: *mut PgConnHandle, ms: u32) -> bool {
    match h.as_ref().and_then(|h| h.inner.as_ref()) {
        Some(c) => c.set_timeout((ms > 0).then(|| Duration::from_millis(ms as u64))).is_ok(),
        None => false,
    }
}

#[no_mangle]
pub unsafe extern "C" fn np_db_results_count(r: *const WkDbResults) -> u64 {
    r.as_ref().map(|r| r.raws.len() as u64).unwrap_or(0)
}

/// Borrowed pointer to result `i`, valid until `np_db_results_free`.
#[no_mangle]
pub unsafe extern "C" fn np_db_results_get(r: *const WkDbResults, i: u64) -> *const WkDbResult {
    match r.as_ref().and_then(|r| r.raws.get(i as usize)) {
        Some(raw) => raw,
        None => std::ptr::null(),
    }
}

/// Frees results; cell data is wiped first.
#[no_mangle]
pub unsafe extern "C" fn np_db_results_free(r: *mut WkDbResults) {
    if !r.is_null() {
        drop(Box::from_raw(r));
    }
}

#[no_mangle]
pub unsafe extern "C" fn np_db_error_free(e: *mut WkDbError) {
    if e.is_null() {
        return;
    }
    let e = Box::from_raw(e);
    for p in [e.sqlstate, e.message, e.detail] {
        if !p.is_null() {
            drop(CString::from_raw(p));
        }
    }
}
