-- Which version of the terms of use and privacy policy each user accepted, and when. Accounts opened before this
-- migration have no record.
ALTER TABLE users ADD COLUMN terms_version VARCHAR(20);
ALTER TABLE users ADD COLUMN terms_accepted_at TIMESTAMP WITH TIME ZONE;

-- A closed account keeps its records (financial history must be retained) but can no longer sign in or receive money.
ALTER TABLE users ADD COLUMN closed_at TIMESTAMP WITH TIME ZONE;
