ALTER TABLE shipments
    ADD COLUMN delivery_stage VARCHAR(20) NOT NULL DEFAULT 'COLLECTED',
    ADD COLUMN stage_updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE shipments
    ADD CONSTRAINT ck_shipments_delivery_stage CHECK (
        delivery_stage IN ('COLLECTED', 'LINE_HAUL_OUT', 'LINE_HAUL_IN', 'OUT_FOR_DELIVERY')
    );
