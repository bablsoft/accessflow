import { DatePicker } from 'antd';
import dayjs from 'dayjs';

const upTo = (limit: number) => Array.from({ length: Math.max(limit, 0) }, (_, i) => i);

/**
 * A future-only datetime picker for key and delegation expiry (#875) — the `ApiKeysSection`
 * treatment: `needConfirm={false}` so a value picked from the panel survives the modal's OK.
 */
export function ExpiresAtPicker({ placeholder }: { placeholder: string }) {
  return (
    <DatePicker
      showTime
      needConfirm={false}
      style={{ width: '100%' }}
      placeholder={placeholder}
      disabledDate={(d) => !!d && d.isBefore(dayjs().startOf('day'))}
      disabledTime={(d) => {
        const now = dayjs();
        if (!d || !d.isSame(now, 'day')) {
          return {};
        }
        return {
          disabledHours: () => upTo(now.hour()),
          disabledMinutes: (hour: number) => (hour === now.hour() ? upTo(now.minute()) : []),
          disabledSeconds: (hour: number, minute: number) =>
            hour === now.hour() && minute === now.minute() ? upTo(now.second()) : [],
        };
      }}
    />
  );
}
