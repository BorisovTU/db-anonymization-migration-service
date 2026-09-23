-- Создание таблиц в БД-источнике (Source DB)
CREATE TABLE IF NOT EXISTS customer_profiles (
    id UUID PRIMARY KEY,
    full_name VARCHAR(255) NOT NULL,
    phone_number VARCHAR(64),
    email VARCHAR(255),
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS customer_attachments (
    id BIGINT PRIMARY KEY,
    customer_id UUID REFERENCES customer_profiles(id),
    file_storage_path VARCHAR(1024) NOT NULL,
    mime_type VARCHAR(128) NOT NULL,
    uploaded_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_src_cust_email ON customer_profiles(email);
CREATE INDEX IF NOT EXISTS idx_src_attach_cust_id ON customer_attachments(customer_id);

-- Потоковая заливка из CSV через команду PostgreSQL COPY
COPY customer_profiles(id, full_name, phone_number, email, created_at)
FROM '/docker-entrypoint-initdb.d/seed-data/customer_profiles.csv'
WITH (FORMAT csv, HEADER true);

COPY customer_attachments(id, customer_id, file_storage_path, mime_type, uploaded_at)
FROM '/docker-entrypoint-initdb.d/seed-data/customer_attachments.csv'
WITH (FORMAT csv, HEADER true);
