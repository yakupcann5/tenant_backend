ALTER TABLE users
  ADD COLUMN first_name VARCHAR(100) NOT NULL DEFAULT '' AFTER id,
  ADD COLUMN last_name VARCHAR(100) NOT NULL DEFAULT '' AFTER first_name;

-- Mevcut name alanını bölüştür (ilk boşluktan böl)
UPDATE users SET
  first_name = SUBSTRING_INDEX(name, ' ', 1),
  last_name = TRIM(SUBSTRING(name FROM LOCATE(' ', name) + 1));

-- Sadece first name olanlar için last_name boş kalmasın
UPDATE users SET last_name = '' WHERE last_name = name AND LOCATE(' ', name) = 0;

ALTER TABLE users DROP COLUMN name;
