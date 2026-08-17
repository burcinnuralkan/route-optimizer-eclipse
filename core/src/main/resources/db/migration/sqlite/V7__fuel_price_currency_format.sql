-- V6'nın fuel_prices'a uygulanmayan yarısı.
--
-- Gerekçe aynı: 'EUR', 'eur' ve 'Euro' ayrı para birimi sayılır, kur araması
-- sessizce boşa düşer. Ve üç tablonun BİRBİRİYLE uyuşması gerekiyor —
-- fuel_prices.currency da exchange_rates.currency ile eşleştiriliyor

CREATE TRIGGER fuel_prices_currency_format_insert
BEFORE INSERT ON fuel_prices
FOR EACH ROW
WHEN NEW.currency IS NULL OR NEW.currency NOT GLOB '[A-Z][A-Z][A-Z]'
BEGIN
    SELECT RAISE(ABORT,
        'fuel_prices.currency üç büyük harfli ISO 4217 kodu olmalı (ör. USD, EUR, TRY)');
END;

CREATE TRIGGER fuel_prices_currency_format_update
BEFORE UPDATE ON fuel_prices
FOR EACH ROW
WHEN NEW.currency IS NULL OR NEW.currency NOT GLOB '[A-Z][A-Z][A-Z]'
BEGIN
    SELECT RAISE(ABORT,
        'fuel_prices.currency üç büyük harfli ISO 4217 kodu olmalı (ör. USD, EUR, TRY)');
END;
