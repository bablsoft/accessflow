import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { JdbcUrlPreview } from './JdbcUrlPreview';

describe('JdbcUrlPreview', () => {
  it('strips the jdbc: prefix and substitutes host, port, and database', () => {
    render(
      <JdbcUrlPreview
        template="jdbc:postgresql://{host}:{port}/{database_name}"
        host="db.internal"
        port={5432}
        databaseName="appdb"
      />,
    );
    expect(
      screen.getByText('postgresql://db.internal:5432/appdb'),
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
      screen.getByText('mysql://{host}:{port}/{database_name}'),
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
      screen.getByText('sqlserver://sqlserver:1433;databaseName=erp'),
    ).toBeInTheDocument();
  });

  it('never surfaces the jdbc: prefix to the user', () => {
    render(
      <JdbcUrlPreview
        template="jdbc:oracle:thin:@//{host}:{port}/{database_name}"
        host="oracle"
        port={1521}
        databaseName="orcl"
      />,
    );
    expect(screen.queryByText(/^jdbc:/)).not.toBeInTheDocument();
    expect(
      screen.getByText('oracle:thin:@//oracle:1521/orcl'),
    ).toBeInTheDocument();
  });
});
