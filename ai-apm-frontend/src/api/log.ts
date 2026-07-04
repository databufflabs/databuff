import http from '../utils/axios'
import { v4 as uuidv4 } from 'uuid'

export default {
  getLogList (data: any) {
    return http.request({
      url: '/log/search',
      method: 'POST',
      data
    })
  },
  /**
   * 获取日志筛选条件
   */
  getLogsCondition: (data?: any) => {
    return http.request({
      url: '/log/conditions',
      method: 'post',
      data,
    });
  },
  getLogTrend: (data?: any) => {
    return http.request({
      url: '/log/trend',
      method: 'post',
      data,
    });
  },
  getLogListMock (data?: any) {
    return Promise.resolve({
      status: 200,
      message: 'SUCCESS',
      data: [
        {id: uuidv4(), creationstamp: new Date().valueOf(), content: '{\n\t"label": "aaaa",\n\t"name": "dsadadadsada",\n\t"create_time": "2022-11-20 18:00:00",\n\t"status": "error",\n\t"host": "192.168.50.110"\n}', status: 'error'},
        {id: uuidv4(), creationstamp: new Date().valueOf(), content: '{\n\t"label": "bbb",\n\t"name": "dsadsdadsada",\n\t"create_time": "2022-11-20 18:00:01",\n\t"status": "error",\n\t"host": "192.168.50.107"\n}', status: 'error'},
        {id: uuidv4(), creationstamp: new Date().valueOf(), content: '{\n\t"label": "ccc",\n\t"name": "dsfdadadsada",\n\t"create_time": "2022-11-20 18:00:00",\n\t"status": "error",\n\t"host": "192.168.50.110"\n}', status: 'error'},
        {id: uuidv4(), creationstamp: new Date().valueOf(), content: '{\n\t"label": "ddd",\n\t"name": "dsadadadsada",\n\t"create_time": "2022-11-20 18:00:01",\n\t"status": "error",\n\t"host": "192.168.50.126"\n}', status: 'error'},
        {id: uuidv4(), creationstamp: new Date().valueOf(), content: '{\n\t"label": "eee",\n\t"name": "ds3dadadsada",\n\t"create_time": "2022-11-20 18:00:00",\n\t"status": "error",\n\t"host": "192.168.50.110"\n}', status: 'error'},
        {id: uuidv4(), creationstamp: new Date().valueOf(), content: '{\n\t"label": "fff",\n\t"name": "dsaggdadsada",\n\t"create_time": "2022-11-20 18:00:01",\n\t"status": "error",\n\t"host": "192.168.50.224"\n}', status: 'error'},
        {id: uuidv4(), creationstamp: new Date().valueOf(), content: '{\n\t"label": "ddd",\n\t"name": "dsaasdadsada",\n\t"create_time": "2022-11-20 18:00:00",\n\t"status": "error",\n\t"host": "192.168.50.5"\n}', status: 'error'},
        {id: uuidv4(), creationstamp: new Date().valueOf(), content: '{\n\t"label": "ggg",\n\t"name": "dsalkdadsada",\n\t"create_time": "2022-11-20 18:00:01",\n\t"status": "error",\n\t"host": "192.168.50.110"\n}', status: 'error'},
      ],
      total: 8,
      scrollId: '1',
      offset: 8,
    })
  }
}
