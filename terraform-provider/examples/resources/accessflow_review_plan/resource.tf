resource "accessflow_review_plan" "standard" {
  name                    = "standard"
  description             = "AI review then one human approval; reads auto-approved."
  requires_ai_review      = true
  requires_human_approval = true
  min_approvals_required  = 1
  approval_timeout_hours  = 24
  auto_approve_reads      = true

  approvers = [
    {
      user_id = "11111111-1111-1111-1111-111111111111"
      stage   = 1
    }
  ]
}
