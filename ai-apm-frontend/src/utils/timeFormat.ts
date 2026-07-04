// 设置日期的秒钟，返回新的日期
import dayjs from 'dayjs';

export const setDateBySeconds = (date: Date | number | string, seconds: number) => {
  const tDate = new Date(date)
  tDate.setSeconds(seconds, 0);
  return tDate;
}

// 根据开始和结束时间戳计算间隔 interval
export const calcInterval = (start: number, end: number) => {
  const duration = Math.abs(Number(end) - Number(start));
  let interval = 300;
  if (duration >= 3600 * 24 * 1000 * 30) { // 30天
    interval = 3600 * 12 // 间隔12小时，60+个点
  } else if (duration >= 3600 * 24 * 1000 * 15) { // 15天
    interval = 3600 * 4 // 间隔4小时，90～179个点
  } else if (duration >= 3600 * 24 * 1000 * 7) { // 7天
    interval = 3600 * 2 // 间隔2小时，84～179个点
  } else if (duration >= 3600 * 24 * 1000 * 3) { // 3天
    interval = 3600 // 间隔1小时，72～167个点
  } else if (duration >= 60 * 60 * 24 * 1000) { // 1天
    interval = 900 // 间隔15分钟，96～287个点
  } else if (duration >= 60 * 60 * 12 * 1000) { // 12小时
    interval = 600 // 间隔10分钟，72～143个点
  } else if (duration >= 60 * 60 * 4 * 1000) { // 4小时
    interval = 300 // 间隔5分钟，48～143个点
  } else if (duration >= 60 * 60 * 2 * 1000) { // 2小时
    interval = 120 // 间隔2分钟，60～119个点
  } else {
    interval = 60 // 间隔1分钟，1～119个点
  }
  return interval
}

/** Compact list header range: same-day end omits the date prefix. */
export function formatCompactTimeRange (
  from: Date | string | number,
  to: Date | string | number,
) {
  const fromDay = dayjs(from);
  const toDay = dayjs(to);
  const fullFormat = 'YYYY-MM-DD HH:mm:ss';
  const timeFormat = 'HH:mm:ss';
  if (!fromDay.isValid() || !toDay.isValid()) {
    return { value0: '', value1: '' };
  }
  return {
    value0: fromDay.format(fullFormat),
    value1: fromDay.isSame(toDay, 'day') ? toDay.format(timeFormat) : toDay.format(fullFormat),
  };
}

/**
 * 根据开始和结束时间戳计算时间范围
 * @param {number} start - 开始时间 单位：ms
 * @param {number} end - 结束时间 单位：ms
 * @param {number} [minDuration] - 最小时间范围，单位：ms，默认值：15分钟
 * @return {Object} - { start, end, and interval }
 */
export const getTimeRange = (start: number, end: number, minDuration = 900000) => {
  // 当前时间戳
  const nowTime = Date.now();
  // 7天前时间戳
  const sevenDaysBefore = nowTime - 7 * 24 * 60 * 60 * 1000
  // 结束时间 > 当前时间，则 结束时间 = 当前时间
  if (end > nowTime) {
    end = nowTime
  }
  // 结束时间和7天前时间戳的间隔 < 最小时间范围，则 结束时间 = 7天前时间戳 + 最小时间范围
  if ((end - sevenDaysBefore) < minDuration) {
    end = sevenDaysBefore + minDuration
  }
  // 开始时间和结束时间的间隔 < 最小时间范围，则 开始时间 = 结束时间 - 最小时间范围
  if ((end - start) < minDuration) {
    start = end - minDuration
  }
  // 开始时间 < 7天前时间戳，则 开始时间 = 7天前时间戳
  if (start < sevenDaysBefore) {
    start = sevenDaysBefore
  }
  return {
    start: +setDateBySeconds(start, 0),
    end: +setDateBySeconds(end, 0),
    interval: calcInterval(start, end),
  }
}
