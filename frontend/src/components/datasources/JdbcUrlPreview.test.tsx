import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { JdbcUrlPreview } from './JdbcUrlPreview';

describe('JdbcUrlPreview', () => {
  it('substitutes host, port, and database into the template', () => {
    render(
      <JdbcUrlPreview
        template="jdbc:postgresql://{host}:{port}/{database_name}"
        host="db.internal"
        port={5432}
        databaseName="appdb"
      />,
    );
    expect(
      screen.getByText('jdbc:postgresql://db.internal:5432/appdb'),
    ).toBeInTheDocument();
  });

  it('keeps placeholder tokens when fields are empty', () => {
    render(
      <JdbcUrlPreview
        template="jdbc:mysql://{host}:{port}/{database_name}"
        host=""
        port={null}
        databaseName=""
      />,
    );
    expect(
      screen.getByText('jdbc:mysql://{host}:{port}/{database_name}'),
    ).toBeInTheDocument();
  });

  it('handles MSSQL semi-colon templates', () => {
    render(
      <JdbcUrlPreview
        template="jdbc:sqlserver://{host}:{port};databaseName={database_name}"
        host="sqlserver"
        port={1433}
        databaseName="erp"
      />,
    );
    expect(
      screen.getByText('jdbc:sqlserver://sqlserver:1433;databaseName=erp'),
    ).toBeInTheDocument();
  });
});
