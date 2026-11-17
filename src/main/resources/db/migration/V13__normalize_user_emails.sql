-- Lower-case and trim e-mails so that uniqueness is effectively case-insensitive.
-- Tolerant: rows whose normalised e-mail would collide with another row are left untouched
-- (resolve such duplicates manually) so the migration can never violate the unique constraint.
UPDATE users u
SET email = lower(trim(u.email))
WHERE u.email <> lower(trim(u.email))
  AND NOT EXISTS (
      SELECT 1 FROM users o
      WHERE o.id <> u.id
        AND lower(trim(o.email)) = lower(trim(u.email))
  );
