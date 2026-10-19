//! Runs against the database started by `np-benchmarks/pg_test_db.sh`. Skipped (passes with a
//! message) when `NP_PG_PASSWORD_FILE` is not set.

use std::time::Duration;
use np_db_postgres::{Config, Connection, ErrorKind, Param};

fn setup() -> Option<(Config, Vec<u8>)> {
    let Ok(path) = std::env::var("NP_PG_PASSWORD_FILE") else {
        eprintln!("NP_PG_PASSWORD_FILE not set; skipping live Postgres tests");
        return None;
    };
    let password = std::fs::read(path).expect("password file");
    let port = std::env::var("NP_PG_PORT").ok().and_then(|p| p.parse().ok()).unwrap_or(55432);
    let mut config = Config::new("127.0.0.1", port, "np", "np");
    config.application_name = "np-db-postgres-test".into();
    config.connect_timeout = Some(Duration::from_secs(5));
    Some((config, password))
}

fn text(p: &str) -> Param<'_> {
    Param { value: Some(p.as_bytes()), type_oid: 0 }
}

fn cell<'a>(r: &'a np_db_postgres::QueryResult, row: usize, col: usize) -> Option<&'a [u8]> {
    let c = r.cells[row * r.columns.len() + col];
    (c.len >= 0).then(|| &r.data[c.offset as usize..(c.offset as i64 + c.len) as usize])
}

#[test]
fn scram_connect_query_params_errors_transactions() {
    let Some((config, password)) = setup() else { return };
    let mut conn = Connection::connect(&config, Some(&password)).expect("connect");
    assert!(conn.parameter("server_version").unwrap().starts_with("17"));
    assert_eq!(conn.tx_status(), b'I');

    let r = conn.execute(b"SELECT $1::int + 1 AS n, $2::text AS t, $3::text AS z", &[text("41"), text("hé"), Param { value: None, type_oid: 0 }]).unwrap();
    assert_eq!(r.columns.len(), 3);
    assert_eq!(r.columns[0].name, "n");
    assert_eq!(r.columns[0].type_oid, 23);
    assert_eq!(cell(&r, 0, 0), Some(&b"42"[..]));
    assert_eq!(cell(&r, 0, 1), Some("hé".as_bytes()));
    assert_eq!(cell(&r, 0, 2), None);

    conn.simple_query(b"DROP TABLE IF EXISTS np_live; CREATE TABLE np_live (id int primary key, v text)").unwrap();
    let ins = conn.execute(b"INSERT INTO np_live VALUES ($1, $2), ($3, $4)", &[text("1"), text("a"), text("2"), text("b")]).unwrap();
    assert_eq!(ins.rows_affected, 2);
    assert!(!ins.has_rows);

    // A server error leaves the connection usable; the detail field carries the row value.
    let err = conn.execute(b"INSERT INTO np_live VALUES ($1, $2)", &[text("1"), text("dup")]).unwrap_err();
    assert_eq!(err.kind, ErrorKind::Server);
    assert_eq!(err.sqlstate, "23505");
    assert!(err.detail.unwrap().contains("(id)=(1)"));
    let r = conn.execute(b"SELECT count(*) FROM np_live", &[]).unwrap();
    assert_eq!(cell(&r, 0, 0), Some(&b"2"[..]));

    // Transactions: status follows ReadyForQuery.
    conn.simple_query(b"BEGIN").unwrap();
    assert_eq!(conn.tx_status(), b'T');
    conn.execute(b"DELETE FROM np_live", &[]).unwrap();
    assert!(conn.execute(b"SELECT 1/0", &[]).is_err());
    assert_eq!(conn.tx_status(), b'E');
    conn.simple_query(b"ROLLBACK").unwrap();
    assert_eq!(conn.tx_status(), b'I');
    let r = conn.execute(b"SELECT v FROM np_live ORDER BY id", &[]).unwrap();
    assert_eq!(r.nrows, 2);
    assert_eq!(cell(&r, 1, 0), Some(&b"b"[..]));

    // Several statements in one simple query give several results.
    let rs = conn.simple_query(b"SELECT 1; SELECT 2, 3; UPDATE np_live SET v = 'c'").unwrap();
    assert_eq!(rs.len(), 3);
    assert_eq!(rs[1].columns.len(), 2);
    assert_eq!(rs[2].rows_affected, 2);

    // Many rows larger than the 64 KB read buffer.
    let r = conn.execute(b"SELECT g, repeat('x', 1000) FROM generate_series(1, 500) g", &[]).unwrap();
    assert_eq!(r.nrows, 500);
    assert_eq!(cell(&r, 499, 0), Some(&b"500"[..]));

    conn.simple_query(b"DROP TABLE np_live").unwrap();
    conn.close();
}

#[test]
fn wrong_password_is_rejected() {
    let Some((config, _)) = setup() else { return };
    let err = Connection::connect(&config, Some(b"not-the-password")).err().expect("must fail");
    assert_eq!(err.kind, ErrorKind::Auth);
    assert_eq!(err.sqlstate, "28P01");
    let err = Connection::connect(&config, None).err().expect("must fail");
    assert_eq!(err.kind, ErrorKind::Auth);
}
