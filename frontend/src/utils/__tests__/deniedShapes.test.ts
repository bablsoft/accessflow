import { describe, expect, it } from 'vitest';
import { DENIED_SHAPES_MAX, deniedShapesPayload, supportsDeniedShapes } from '../deniedShapes';

describe('deniedShapes', () => {
  it('mirrors the backend size cap', () => {
    expect(DENIED_SHAPES_MAX).toBe(8);
  });

  it('is supported on the in-process relational engines only', () => {
    expect(supportsDeniedShapes('POSTGRESQL')).toBe(true);
    expect(supportsDeniedShapes('MYSQL')).toBe(true);
    expect(supportsDeniedShapes('MONGODB')).toBe(false);
    expect(supportsDeniedShapes('REDIS')).toBe(false);
  });

  it('drops an empty selection from the payload', () => {
    expect(deniedShapesPayload(undefined)).toBeNull();
    expect(deniedShapesPayload([])).toBeNull();
    expect(deniedShapesPayload(['JOIN', 'CTE'])).toEqual(['JOIN', 'CTE']);
  });
});
