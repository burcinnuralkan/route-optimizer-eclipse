-- Bu tablo maliyet değil, FİYAT tutar: "1 kg yakıt o gün kaça satılıyordu".

CREATE TABLE fuel_prices (
    price_date       TEXT             NOT NULL,
    -- Tip serbest değil, iki değer: JET_A1 ve SAF. Sebep cost_types'tan farklı —
    -- orada yeni bir tip yalnızca bir muhasebe kırılımıydı; burada her tipin
    -- hesapta ayrı bir karşılığı var (FuelParams.jetFuelPricePerKg /
    -- safPricePerKg) ve üçüncü bir tip kod değişikliği gerektirir.
    fuel_type        TEXT             NOT NULL,
    currency         TEXT             NOT NULL,
    price_per_kg     DOUBLE PRECISION NOT NULL,
    -- Çevrimde kullanılan kur ve dolar karşılığı SAKLANIR, okumada yeniden
    -- hesaplanmaz: kur tablosundaki bir düzeltme geçmiş raporu değiştirmesin.
    rate_to_usd      DOUBLE PRECISION NOT NULL,
    price_per_kg_usd DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (price_date, fuel_type)
);

INSERT INTO fuel_prices
    (price_date, fuel_type, currency, price_per_kg, rate_to_usd, price_per_kg_usd)
VALUES
    ('1970-01-01', 'JET_A1', 'USD', 0.85, 1.0, 0.85),
    ('1970-01-01', 'SAF',    'USD', 2.60, 1.0, 2.60);
 