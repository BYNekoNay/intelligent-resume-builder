/**
 * 示例简历数据 — 帮助新用户了解每个栏目该怎么填写。
 * 点击"加载示例"后，编辑器会用这份数据填充所有字段。
 */
const sampleResume = {
  basics: {
    name: '张明远',
    title: '高级后端工程师',
    email: 'zhangmingyuan@example.com',
    phone: '138-0000-1234',
    location: '上海市 · 浦东新区',
    summary:
      '6 年后端研发经验，专注 Java / Spring 生态与高并发系统设计。主导过日活百万级电商平台的订单核心链路改造，将峰值 QPS 从 800 提升至 3200。善于从业务目标反推技术方案，推动跨团队协作落地。',
  },
  work: [
    {
      company: '字节跳动',
      position: '高级后端开发工程师',
      startDate: '2022-03',
      endDate: '至今',
      description:
        '负责电商交易中台订单域的核心服务开发与架构演进，直接支撑抖音电商日均千万级订单处理。',
      highlights: [
        '主导订单状态机重构，将状态流转延迟从 80ms 降至 12ms，线上故障率下降 62%。',
        '设计并落地订单读写分离方案，读接口 P99 延迟从 350ms 降至 45ms，支撑大促峰值 QPS 3200。',
        '搭建订单域全链路压测体系，提前发现 11 处瓶颈点，双十一期间零故障。',
        '带领 3 人小组完成订单逆向流程（退款/退货）微服务化拆分，系统可用性从 99.9% 提升至 99.99%。',
      ],
    },
    {
      company: '美团',
      position: '后端开发工程师',
      startDate: '2019-07',
      endDate: '2022-02',
      description:
        '参与外卖配送调度系统的研发，负责骑手侧运单分配与路径规划相关的后台服务。',
      highlights: [
        '优化骑手运单分配算法，将平均匹配耗时从 2.3s 缩短至 0.7s，配送准时率提升 4.2pp。',
        '开发骑手履约监控看板，实时追踪 30+ 核心指标，帮助运营团队将异常订单处理时效提升 40%。',
        '重构消息推送模块，引入 RocketMQ 事务消息保证配送状态变更的最终一致性。',
      ],
    },
  ],
  skills: [
    { name: 'Java' },
    { name: 'Spring Boot / Spring Cloud' },
    { name: 'MySQL 调优与分库分表' },
    { name: 'Redis 缓存设计' },
    { name: 'Kafka / RocketMQ' },
    { name: 'DDD 领域驱动设计' },
    { name: 'Docker / Kubernetes' },
    { name: '系统性能调优' },
  ],
  projects: [
    {
      name: '电商平台订单中心微服务化',
      role: '技术负责人',
      description:
        '将单体应用中的订单模块拆分为独立的订单中心微服务，支撑公司从垂直电商向平台化转型的战略需求。',
      highlights: [
        '设计订单域限界上下文，将订单、支付、物流三个子域解耦，服务拆分后各域独立部署与迭代。',
        '实现基于 ShardingSphere 的分库分表方案，单表数据量从 2 亿降至 2000 万，查询性能提升 8 倍。',
        '引入 CQRS 模式分离读写模型，读侧通过 Canal + ES 实现毫秒级搜索响应。',
      ],
    },
    {
      name: '实时数据同步管道',
      role: '核心开发者',
      description:
        '搭建基于 Flink CDC 的实时数据同步管道，将核心业务数据实时同步至数据仓库与搜索引擎。',
      highlights: [
        '基于 Flink CDC 实现 MySQL → Kafka → Hive/ES 的实时链路，端到端延迟 < 5s。',
        '设计断点续传与数据对账机制，确保全量 + 增量场景下数据零丢失。',
      ],
    },
  ],
  education: [
    {
      school: '浙江大学',
      degree: '硕士',
      major: '计算机科学与技术',
      startDate: '2017-09',
      endDate: '2019-06',
    },
    {
      school: '华中科技大学',
      degree: '本科',
      major: '软件工程',
      startDate: '2013-09',
      endDate: '2017-06',
    },
  ],
  certificates: [
    {
      name: 'AWS Solutions Architect Associate',
      issuer: 'Amazon Web Services',
      date: '2023-06',
    },
    {
      name: 'PMP 项目管理认证',
      issuer: 'PMI',
      date: '2021-11',
    },
  ],
  languages: [
    { name: '英语', level: 'CET-6 · 专业工作沟通与技术文档阅读' },
  ],
  template: { code: 'classic' },
}

export default sampleResume
