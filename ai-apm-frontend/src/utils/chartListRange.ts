import dayjs from 'dayjs';

export const DEFAULT_CHART_LIST_LIMIT = 1000;

export interface RecentChartListRangeOptions {
  interval: number;
  globalToTime: string | Date;
  limit?: number;
}

export interface RecentChartListRange {
  fromMs: number;
  toMs: number;
  fromTime: string;
  toTime: string;
}

/** Walk chart volume buckets from newest to oldest until {@code limit} records are covered. */
export function resolveRecentRangeFromCounts (
  cntMap: Record<string, number | null | undefined> | null | undefined,
  options: RecentChartListRangeOptions,
): RecentChartListRange | null {
  const limit = options.limit ?? DEFAULT_CHART_LIST_LIMIT;
  const intervalMs = Math.max(60, options.interval || 60) * 1000;
  const buckets = Object.entries(cntMap || {})
    .map(([key, value]) => ({
      bucketMs: Number(key),
      count: Number(value) || 0,
    }))
    .filter((item) => Number.isFinite(item.bucketMs) && item.count > 0)
    .sort((left, right) => right.bucketMs - left.bucketMs);

  if (!buckets.length) {
    return null;
  }

  let total = 0;
  let oldestBucketMs = buckets[0].bucketMs;
  for (const bucket of buckets) {
    total += bucket.count;
    oldestBucketMs = bucket.bucketMs;
    if (total >= limit) {
      break;
    }
  }

  const newestBucketMs = buckets[0].bucketMs;
  const fromMs = oldestBucketMs;
  const bucketEndMs = newestBucketMs + intervalMs;
  const globalToMs = dayjs(options.globalToTime).valueOf();
  const toMs = Number.isFinite(globalToMs) ? Math.min(bucketEndMs, globalToMs) : bucketEndMs;

  return {
    fromMs,
    toMs,
    fromTime: dayjs(fromMs).format('YYYY-MM-DD HH:mm:ss'),
    toTime: dayjs(toMs).format('YYYY-MM-DD HH:mm:ss'),
  };
}
