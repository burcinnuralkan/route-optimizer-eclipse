-- Para birimi kodunu ISO 4217 biçimine (üç BÜYÜK harf) bağlar.

CREATE TRIGGER costs_currency_format_insert
BEFORE INSERT ON costs
FOR EACH ROW
WHEN NEW.currency IS NULL OR NEW.currency NOT GLOB '[A-Z][A-Z][A-Z]'
BEGIN
    SELECT RAISE(ABORT,
        'costs.currency üç büyük harfli ISO 4217 kodu olmalı (ör. USD, EUR, TRY)');
END;

CREATE TRIGGER costs_currency_format_update
BEFORE UPDATE ON costs
FOR EACH ROW
WHEN NEW.currency IS NULL OR NEW.currency NOT GLOB '[A-Z][A-Z][A-Z]'
BEGIN
    SELECT RAISE(ABORT,
        'costs.currency üç büyük harfli ISO 4217 kodu olmalı (ör. USD, EUR, TRY)');
END;

CREATE TRIGGER exchange_rates_currency_format_insert
BEFORE INSERT ON exchange_rates
FOR EACH ROW
WHEN NEW.currency IS NULL OR NEW.currency NOT GLOB '[A-Z][A-Z][A-Z]'
BEGIN
    SELECT RAISE(ABORT,
        'exchange_rates.currency üç büyük harfli ISO 4217 kodu olmalı (ör. EUR, TRY)');
END;

CREATE TRIGGER exchange_rates_currency_format_update
BEFORE UPDATE ON exchange_rates
FOR EACH ROW
WHEN NEW.currency IS NULL OR NEW.currency NOT GLOB '[A-Z][A-Z][A-Z]'
BEGIN
    SELECT RAISE(ABORT,
        'exchange_rates.currency üç büyük harfli ISO 4217 kodu olmalı (ör. EUR, TRY)');
END;
 