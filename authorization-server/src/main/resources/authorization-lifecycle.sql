CREATE TABLE IF NOT EXISTS oauth_lifecycle_lock (id INTEGER PRIMARY KEY);
MERGE INTO oauth_lifecycle_lock KEY(id) VALUES (1);
CREATE TABLE IF NOT EXISTS oauth_refresh_history (
    token_hash VARCHAR(64) PRIMARY KEY,
    authorization_id VARCHAR(100) NOT NULL,
    client_id VARCHAR(100) NOT NULL,
    expires_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS oauth_refresh_family ON oauth_refresh_history(authorization_id);
