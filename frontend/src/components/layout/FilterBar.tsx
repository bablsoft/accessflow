import type { ReactNode } from 'react';
import { Input } from 'antd';
import type { InputProps } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import './layout.css';

/** The filter row under a page header. Filters wrap; `FilterBar.Count` sits at the far end. */
export function FilterBar({ children }: { children: ReactNode }) {
  return (
    <div className="af-filter-bar" role="search">
      {children}
    </div>
  );
}

function FilterBarSearch(props: InputProps) {
  return (
    <Input
      allowClear
      prefix={<SearchOutlined aria-hidden />}
      {...props}
      className={['af-filter-bar-search', props.className].filter(Boolean).join(' ')}
    />
  );
}

function FilterBarCount({ children }: { children: ReactNode }) {
  return (
    <span className="af-filter-bar-count" aria-live="polite">
      {children}
    </span>
  );
}

FilterBar.Search = FilterBarSearch;
FilterBar.Count = FilterBarCount;
