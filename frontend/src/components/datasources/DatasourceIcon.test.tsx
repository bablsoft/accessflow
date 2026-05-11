import { describe, expect, it } from 'vitest';
import { render } from '@testing-library/react';
import { DatasourceIcon } from './DatasourceIcon';

describe('DatasourceIcon', () => {
  it('renders the Devicon SVG for each supported DbType', () => {
    const cases = [
      { dbType: 'POSTGRESQL' as const, src: '/db-icons/postgresql.svg' },
      { dbType: 'MYSQL' as const, src: '/db-icons/mysql.svg' },
      { dbType: 'MARIADB' as const, src: '/db-icons/mariadb.svg' },
      { dbType: 'ORACLE' as const, src: '/db-icons/oracle.svg' },
      { dbType: 'MSSQL' as const, src: '/db-icons/mssql.svg' },
    ];
    for (const { dbType, src } of cases) {
      const { container, unmount } = render(<DatasourceIcon dbType={dbType} />);
      const img = container.querySelector('img');
      expect(img).not.toBeNull();
      expect(img!.getAttribute('src')).toBe(src);
      expect(img!.getAttribute('width')).toBe('36');
      expect(img!.getAttribute('height')).toBe('36');
      unmount();
    }
  });

  it('honors a custom size', () => {
    const { container } = render(<DatasourceIcon dbType="POSTGRESQL" size={48} />);
    const img = container.querySelector('img');
    expect(img!.getAttribute('width')).toBe('48');
    expect(img!.getAttribute('height')).toBe('48');
  });

  it('falls back to generic.svg for an unknown DbType', () => {
    // Cast through unknown so the test covers the runtime fallback even though
    // TypeScript narrows DbType to the supported five values at compile time.
    const unknownDbType = 'CASSANDRA' as unknown as 'POSTGRESQL';
    const { container } = render(<DatasourceIcon dbType={unknownDbType} />);
    const img = container.querySelector('img');
    expect(img!.getAttribute('src')).toBe('/db-icons/generic.svg');
  });
});
