-- Clean up any invalid phone values (such as emails erroneously entered into phone fields)
UPDATE users
SET phone = NULL
WHERE phone IS NOT NULL AND (phone LIKE '%@%' OR NOT (phone ~ '^(?:\+84|0)[0-9]{9,10}$'));

