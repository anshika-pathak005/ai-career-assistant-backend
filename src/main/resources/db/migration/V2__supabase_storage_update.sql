-- V2: Migration to support Supabase Storage (schema-only, no data migration)
-- This migration ensures schema compatibility with Supabase Storage integration
-- Existing file_path data remains unchanged

-- Note: No actual schema changes needed as existing file_path field already supports
-- both local disk paths and Supabase Storage paths (resumes/{userId}/{uuid_filename})