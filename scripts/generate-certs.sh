#!/bin/sh
set -eu

cert_dir="${1:-/certs}"
: "${TLS_KEYSTORE_PASSWORD:?TLS_KEYSTORE_PASSWORD must be set}"
mkdir -p "$cert_dir"
if [ ! -f "$cert_dir/server.p12" ] || [ ! -f "$cert_dir/server.crt" ]; then
    rm -f "$cert_dir/server.p12" "$cert_dir/server.crt"
    keytool -genkeypair -alias server -keyalg RSA -keysize 3072 -validity 365 \
        -storetype PKCS12 -keystore "$cert_dir/server.p12" \
        -storepass:env TLS_KEYSTORE_PASSWORD -keypass:env TLS_KEYSTORE_PASSWORD \
        -dname "CN=localhost" \
        -ext "SAN=dns:localhost,dns:server,dns:server-a,dns:server-b,ip:127.0.0.1" \
        -ext "EKU=serverAuth" -noprompt
    keytool -exportcert -rfc -alias server -keystore "$cert_dir/server.p12" \
        -storepass:env TLS_KEYSTORE_PASSWORD -file "$cert_dir/server.crt"
fi
chown 10001:10001 "$cert_dir/server.p12"
chmod 640 "$cert_dir/server.p12"
chmod 644 "$cert_dir/server.crt"
