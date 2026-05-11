import type { DbType } from '@/types/api';

const ICON_BY_DB_TYPE: Record<DbType, string> = {
  POSTGRESQL: '/db-icons/postgresql.svg',
  MYSQL: '/db-icons/mysql.svg',
  MARIADB: '/db-icons/mariadb.svg',
  ORACLE: '/db-icons/oracle.svg',
  MSSQL: '/db-icons/mssql.svg',
};

const FALLBACK_ICON = '/db-icons/generic.svg';

interface DatasourceIconProps {
  dbType: DbType;
  size?: number;
  className?: string;
  style?: React.CSSProperties;
}

export function DatasourceIcon({ dbType, size = 36, className, style }: DatasourceIconProps) {
  return (
    <img
      src={ICON_BY_DB_TYPE[dbType] ?? FALLBACK_ICON}
      alt=""
      width={size}
      height={size}
      className={className}
      style={{ objectFit: 'contain', ...style }}
    />
  );
}
