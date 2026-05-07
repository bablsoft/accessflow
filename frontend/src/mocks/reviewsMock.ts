// Temporary stand-in until FE-03 wires the /reviews/{id}/approve and /reviews/{id}/reject
// endpoints. Approving / rejecting from the demo UI no-ops here — the backend remains the
// source of truth and won't transition the query.
import { jittered } from './delay';

export async function approveQueryMock(_id: string, _comment?: string): Promise<void> {
  await jittered(150, 350);
}

export async function rejectQueryMock(_id: string, _comment?: string): Promise<void> {
  await jittered(150, 350);
}
