package client

import "context"

// NotificationChannel has no GET /{id} endpoint, so Read lists and filters by id.
// Sensitive config sub-fields are masked on the response, so the provider keeps the
// configured `config` from state rather than refreshing it.
type NotificationChannel struct {
	ID             string         `json:"id"`
	OrganizationID string         `json:"organization_id"`
	ChannelType    string         `json:"channel_type"`
	Name           string         `json:"name"`
	Config         map[string]any `json:"config"`
	Active         bool           `json:"active"`
}

type CreateNotificationChannelRequest struct {
	Name        string            `json:"name"`
	ChannelType string            `json:"channel_type"`
	Config      map[string]string `json:"config"`
}

type UpdateNotificationChannelRequest struct {
	Name   *string           `json:"name,omitempty"`
	Config map[string]string `json:"config,omitempty"`
	Active *bool             `json:"active,omitempty"`
}

func (c *Client) CreateNotificationChannel(ctx context.Context, req CreateNotificationChannelRequest) (*NotificationChannel, error) {
	var out NotificationChannel
	if err := c.do(ctx, "POST", "/admin/notification-channels", req, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) ListNotificationChannels(ctx context.Context) ([]NotificationChannel, error) {
	var out []NotificationChannel
	if err := c.do(ctx, "GET", "/admin/notification-channels", nil, &out); err != nil {
		return nil, err
	}
	return out, nil
}

// GetNotificationChannel finds a channel by id via the list endpoint, returning a 404
// APIError when absent so Read can drop it from state.
func (c *Client) GetNotificationChannel(ctx context.Context, id string) (*NotificationChannel, error) {
	channels, err := c.ListNotificationChannels(ctx)
	if err != nil {
		return nil, err
	}
	for i := range channels {
		if channels[i].ID == id {
			return &channels[i], nil
		}
	}
	return nil, &APIError{StatusCode: 404, Title: "Not Found", Detail: "notification channel " + id + " not found"}
}

func (c *Client) UpdateNotificationChannel(ctx context.Context, id string, req UpdateNotificationChannelRequest) (*NotificationChannel, error) {
	var out NotificationChannel
	if err := c.do(ctx, "PUT", "/admin/notification-channels/"+id, req, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) DeleteNotificationChannel(ctx context.Context, id string) error {
	return c.do(ctx, "DELETE", "/admin/notification-channels/"+id, nil, nil)
}
