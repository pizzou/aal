# AAL production load-test targets

Use k6/Gatling/JMeter against a staging deployment before production. Required scenarios:
- dashboard reads and shipment search with 100k+ shipments
- concurrent booking/quote conversion
- concurrent inventory movements
- concurrent payments with the same idempotency key
- GPS ingestion at fleet peak rate through Kafka
- Redis current-position reads
- large document listing/download authorization
