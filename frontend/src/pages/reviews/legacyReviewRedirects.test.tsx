import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation, useNavigationType } from 'react-router-dom';
import { ApiReviewsRedirect, LegacyDeploymentReviewsRedirect } from './legacyReviewRedirects';

function LocationProbe() {
  const location = useLocation();
  const navigationType = useNavigationType();
  return (
    <div data-testid="probe" data-nav-type={navigationType}>
      {location.pathname}
      {location.search}
    </div>
  );
}

function renderAt(url: string) {
  return render(
    <MemoryRouter initialEntries={[url]}>
      <Routes>
        <Route path="/api-reviews" element={<ApiReviewsRedirect />} />
        <Route path="/deployment-reviews" element={<LegacyDeploymentReviewsRedirect />} />
        <Route path="/reviews" element={<LocationProbe />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('legacy review-queue redirects (#772)', () => {
  it('sends /api-reviews to the API tab of the hub', () => {
    renderAt('/api-reviews');
    expect(screen.getByTestId('probe')).toHaveTextContent('/reviews?tab=api');
  });

  it('sends /deployment-reviews to the Deployments tab of the hub', () => {
    renderAt('/deployment-reviews');
    expect(screen.getByTestId('probe')).toHaveTextContent('/reviews?tab=deployments');
  });

  it('keeps the rollback worklist deep link pointing at the Rollbacks tab', () => {
    renderAt('/deployment-reviews?tab=rollbacks');
    expect(screen.getByTestId('probe')).toHaveTextContent('/reviews?tab=rollbacks');
  });

  it('replaces the legacy entry rather than pushing onto the history stack', () => {
    renderAt('/deployment-reviews?tab=whatever');
    const probe = screen.getByTestId('probe');
    expect(probe).toHaveTextContent('/reviews?tab=deployments');
    expect(probe.dataset.navType).toBe('REPLACE');
  });
});
