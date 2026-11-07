-- Support bounded history traversal in immutable creation/ID order for each account.
CREATE INDEX idx_app_owner_created ON applications(owner_id, deleted, created_at, id);
