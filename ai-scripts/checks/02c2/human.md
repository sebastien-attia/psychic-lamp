# Human checks — Phase 02c2 Terraform
□ Module outputs expose only what root main.tf needs (no leaky exports).
□ `terraform plan` against a real subscription shows the expected resource count (dry-run in a sandbox, or review the planned diff).
□ All sensitive variables are marked `sensitive = true`.
