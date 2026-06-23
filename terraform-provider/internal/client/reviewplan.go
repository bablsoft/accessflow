package client

import "context"

type ReviewPlanApprover struct {
	UserID *string `json:"user_id,omitempty"`
	Role   *string `json:"role,omitempty"`
	Stage  int64   `json:"stage"`
}

type ReviewPlan struct {
	ID                    string               `json:"id"`
	OrganizationID        string               `json:"organization_id"`
	Name                  string               `json:"name"`
	Description           *string              `json:"description"`
	RequiresAIReview      bool                 `json:"requires_ai_review"`
	RequiresHumanApproval bool                 `json:"requires_human_approval"`
	MinApprovalsRequired  int64                `json:"min_approvals_required"`
	ApprovalTimeoutHours  *int64               `json:"approval_timeout_hours"`
	AutoApproveReads      bool                 `json:"auto_approve_reads"`
	NotifyChannels        []string             `json:"notify_channels"`
	Approvers             []ReviewPlanApprover `json:"approvers"`
}

type ReviewPlanRequest struct {
	Name                  *string              `json:"name,omitempty"`
	Description           *string              `json:"description,omitempty"`
	RequiresAIReview      *bool                `json:"requires_ai_review,omitempty"`
	RequiresHumanApproval *bool                `json:"requires_human_approval,omitempty"`
	MinApprovalsRequired  *int64               `json:"min_approvals_required,omitempty"`
	ApprovalTimeoutHours  *int64               `json:"approval_timeout_hours,omitempty"`
	AutoApproveReads      *bool                `json:"auto_approve_reads,omitempty"`
	NotifyChannels        []string             `json:"notify_channels,omitempty"`
	Approvers             []ReviewPlanApprover `json:"approvers,omitempty"`
}

func (c *Client) CreateReviewPlan(ctx context.Context, req ReviewPlanRequest) (*ReviewPlan, error) {
	var out ReviewPlan
	if err := c.do(ctx, "POST", "/review-plans", req, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) GetReviewPlan(ctx context.Context, id string) (*ReviewPlan, error) {
	var out ReviewPlan
	if err := c.do(ctx, "GET", "/review-plans/"+id, nil, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) UpdateReviewPlan(ctx context.Context, id string, req ReviewPlanRequest) (*ReviewPlan, error) {
	var out ReviewPlan
	if err := c.do(ctx, "PUT", "/review-plans/"+id, req, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) DeleteReviewPlan(ctx context.Context, id string) error {
	return c.do(ctx, "DELETE", "/review-plans/"+id, nil, nil)
}
