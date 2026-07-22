import { ref } from 'vue'

export type Locale = 'zh-CN' | 'en-US'

interface MessageTree {
  [key: string]: string | MessageTree
}

const STORAGE_KEY = 'intelligent-resume.locale'

const messages: Record<Locale, MessageTree> = {
  'zh-CN': {
    brand: '智历',
    navigation: {
      label: '主导航',
      home: '工作台', resumes: '简历', materials: '资料库', materialGeneration: '素材生成',
      jobs: 'JD', ats: '体检', applications: '投递', interviews: '面试', imports: '导入',
      communications: '文案', answerAssets: '答案库', achievements: '成就引导',
    },
    actions: { aiConsent: 'AI 数据处理同意', signOut: '退出登录', signIn: '登录', signUp: '注册' },
    language: { label: '界面语言', chinese: '中文', english: 'EN' },
    auth: {
      account: '账户', login: '登录', register: '创建账户', username: '用户名', email: '邮箱', password: '密码',
      loggingIn: '正在登录...', registering: '正在创建...', loginError: '登录失败，请检查账号和密码后重试。',
      registerError: '注册失败。用户名或邮箱可能已被使用，请检查后重试。',
      noAccount: '还没有账户？', hasAccount: '已有账户？', returnHome: '返回工作台',
    },
    assets: {
      eyebrow: '答案库', title: '面试答案资产', subtitle: '保留你的原始回答与 AI 建议，便于诚实复盘面试表现。',
      job: '岗位', allJobs: '全部岗位', keyword: '关键词', searchPlaceholder: '搜索问题、建议或反馈', search: '搜索',
      create: '新建答案资产', edit: '编辑答案资产', question: '问题', original: '原始回答', suggested: 'AI 建议',
      saving: '正在保存...', save: '保存资产', saveChanges: '保存修改', cancel: '取消', editAction: '编辑', delete: '删除',
      loading: '正在加载答案资产...', empty: '还没有答案资产。', loadError: '无法加载答案资产，请稍后重试。',
      saveError: '无法保存答案资产。请检查必填项后重试。', deleteError: '无法删除答案资产，请稍后重试。', confirmDelete: '删除这条答案资产？',
    },
    jobs: {
      eyebrow: '岗位描述', title: '目标岗位', subtitle: '保存岗位描述，选择简历与资料偏好，然后生成可逐项审阅的定制草稿。',
      create: '新建岗位描述', edit: '编辑岗位描述', roleTitle: '岗位名称', company: '公司', description: '岗位描述',
      saving: '正在保存...', save: '保存岗位', saveChanges: '保存修改', cancel: '取消', parseResult: '解析结果',
      setup: '定制草稿设置', targetResume: '目标简历', selectResume: '选择简历', manageConsent: '管理 AI 数据处理同意',
      preferences: '资料偏好', useDefault: '使用资料默认偏好', alwaysInclude: '始终使用', prefer: '优先使用', exclude: '不使用',
      loading: '正在加载岗位描述...', empty: '还没有岗位描述。', noCompany: '未填写公司', parsed: '已解析', notParsed: '未解析',
      editAction: '编辑', parse: '解析', starting: '正在启动...', generate: '生成草稿', delete: '删除',
      saveError: '无法保存岗位描述。请检查必填项后重试。', parseError: '无法解析该岗位描述，请稍后重试。', deleteError: '无法删除该岗位描述，它可能正在被使用。',
      confirmDelete: '删除这条岗位描述？', selectTarget: '请先创建并选择一份目标简历，再生成定制草稿。', generateError: '无法启动生成。请确认 AI 数据处理同意，并检查所选简历和资料。',
    },
    ats: { eyebrow: '简历体检', title: 'ATS 规则检查', disclaimer: '这是基于规则的简历体检，不是企业 ATS 结果或录用预测。', resume: '简历', version: '简历版本', job: '目标岗位', selectResume: '选择简历', selectVersion: '选择版本', selectJob: '选择岗位', checking: '正在检查...', run: '开始检查', score: '健康得分', structure: '结构', coverage: '关键词覆盖度', priorities: '优先修改项', passed: '已通过检查', risks: '风险与依据', noPriorities: '未发现紧急结构问题。', noRisks: '未发现结构风险。', selectError: '请先选择简历版本和目标岗位。', runError: '无法执行简历体检，请确认所选资源归属于当前账户。' },
    import: { eyebrow: '简历导入', title: '导入已有简历', subtitle: '上传不超过 5 MB 的 PDF、DOCX 或 TXT 文件。系统只解析文件，不会保存源文件。', file: '简历文件', parsing: '正在解析...', parse: '解析文本', fileError: '请先选择 PDF、DOCX 或 TXT 文件。', parseError: '无法解析该文件，请检查格式、大小和内容。', reviewError: '请检查提取出的文本后再继续。', stored: '是否保存源文件', yes: '是', no: '否', review: '请检查并修正文本后再用于生成。', extracted: '提取出的文本', use: '使用修正后的文本', preview: '规范化输入预览' },
    communication: { eyebrow: '求职文案', title: '岗位沟通文案', subtitle: '创建可编辑的求职沟通文案，系统不会自动发送到任何外部平台。', resume: '简历', version: '简历版本', job: '岗位描述', type: '文案类型', selectResume: '选择简历', selectVersion: '选择版本', selectJob: '选择岗位', cover: '求职信', email: '邮件正文', opening: '开场消息', generating: '正在生成...', generate: '生成文案', verify: '复制或关联到投递前，请核对文案中的事实。', draft: '可编辑文案', copy: '复制', use: '用于投递', selectError: '请先选择简历版本和目标岗位。', generateError: '无法生成文案，请检查 AI 数据处理同意及所选资源。', copied: '文案已复制。', clipboardError: '无法访问剪贴板，请手动选择文本复制。' },
    applications: { eyebrow: '投递管理', title: '投递记录', subtitle: '管理你手动跟进的投递，系统不会自动发送任何内容。', create: '新建投递', edit: '编辑投递', resume: '简历', version: '简历版本', job: '岗位描述', selectResume: '选择简历', selectVersion: '选择版本', selectJob: '选择岗位', cover: '求职信', email: '邮件正文', opening: '开场消息', saving: '正在保存...', createDraft: '创建草稿', saveChanges: '保存修改', cancel: '取消', loading: '正在加载投递记录...', empty: '还没有投递记录。', editAction: '编辑', delete: '删除', feedback: '反馈', saveFeedback: '保存反馈', application: '投递', jobRef: '岗位', versionRef: '简历版本' },
    home: {
      eyebrow: '智历 · 在群星之间书写简历', title: '把简历放进银河，\n让每一份事实可追溯。',
      description: '智历是面向求职者的星图工具：沉淀职业资料、按 JD 定制、逐项确认 AI 草稿、规则覆盖度反馈，最后通过受控 PDF 私有下载。所有事实可追溯，所有数据留在你的账户。',
      start: '开始使用', docs: '查看文档', materials: '进入资料库', features: '核心能力', featureTitle: '四个模块，串起一条完整链路',
      featureDescription: '从「沉淀」到「导出」，中间不离开智历。所有步骤保留审计痕迹。',
      flow: '主流程', flowTitle: '从资料到 PDF，五步闭环', flowDescription: '每一步可中断可恢复，刷新页面也能继续。',
      service: '服务状态', serviceTitle: '服务心跳正常，准备就绪', creating: '创建账户', tryJobs: '体验 JD 流程',
      statModules: '核心模块', statSteps: '流程步骤', statTraceable: '来源可追溯', statLlm: '外部大模型',
    },
  },
  'en-US': {
    brand: 'ZhiLi',
    navigation: {
      label: 'Primary navigation',
      home: 'Workspace', resumes: 'Resumes', materials: 'Materials', materialGeneration: 'Generate',
      jobs: 'Jobs', ats: 'Review', applications: 'Applications', interviews: 'Interviews', imports: 'Import',
      communications: 'Messages', answerAssets: 'Answer library', achievements: 'Achievements',
    },
    actions: { aiConsent: 'AI data consent', signOut: 'Sign out', signIn: 'Sign in', signUp: 'Create account' },
    language: { label: 'Interface language', chinese: '中文', english: 'EN' },
    auth: {
      account: 'Account', login: 'Sign in', register: 'Create account', username: 'Username', email: 'Email', password: 'Password',
      loggingIn: 'Signing in...', registering: 'Creating account...', loginError: 'Sign-in failed. Check your username and password, then try again.',
      registerError: 'Registration failed. Your username or email may already be in use.',
      noAccount: 'New here?', hasAccount: 'Already have an account?', returnHome: 'Back to workspace',
    },
    assets: {
      eyebrow: 'Answer library', title: 'Interview Answer Assets', subtitle: 'Keep your original response and AI suggestion separate for honest interview review.',
      job: 'Job', allJobs: 'All jobs', keyword: 'Keyword', searchPlaceholder: 'Search question, suggestion, or feedback', search: 'Search',
      create: 'New answer asset', edit: 'Edit answer asset', question: 'Question', original: 'Original answer', suggested: 'AI suggestion',
      saving: 'Saving...', save: 'Save asset', saveChanges: 'Save changes', cancel: 'Cancel', editAction: 'Edit', delete: 'Delete',
      loading: 'Loading answer assets...', empty: 'No answer assets yet.', loadError: 'Unable to load answer assets. Please try again.',
      saveError: 'Unable to save this answer asset. Check the required fields and try again.', deleteError: 'Unable to delete this answer asset. Please try again.', confirmDelete: 'Delete this answer asset?',
    },
    jobs: {
      eyebrow: 'Job descriptions', title: 'Target Jobs', subtitle: 'Save a job description, select a resume and material preferences, then generate a reviewable tailored draft.',
      create: 'New job description', edit: 'Edit job description', roleTitle: 'Role title', company: 'Company', description: 'Job description',
      saving: 'Saving...', save: 'Save job', saveChanges: 'Save changes', cancel: 'Cancel', parseResult: 'parsing result',
      setup: 'Tailored draft setup', targetResume: 'Target resume', selectResume: 'Select a resume', manageConsent: 'Manage AI consent',
      preferences: 'Material preferences', useDefault: 'Use material default', alwaysInclude: 'Always include', prefer: 'Prefer', exclude: 'Exclude',
      loading: 'Loading job descriptions...', empty: 'No job descriptions yet.', noCompany: 'No company specified', parsed: 'Parsed', notParsed: 'Not parsed',
      editAction: 'Edit', parse: 'Parse', starting: 'Starting...', generate: 'Generate draft', delete: 'Delete',
      saveError: 'Unable to save the job description. Check the required fields and try again.', parseError: 'Unable to parse this job description. Please try again.', deleteError: 'Unable to delete this job description. It may already be in use.',
      confirmDelete: 'Delete this job description?', selectTarget: 'Create and select a target resume before generating a tailored draft.', generateError: 'Unable to start generation. Confirm AI consent and review the selected resume and materials.',
    },
    ats: { eyebrow: 'Resume health', title: 'ATS rule check', disclaimer: 'This is a rules-based resume health check, not an enterprise ATS result or hiring prediction.', resume: 'Resume', version: 'Resume version', job: 'Target job', selectResume: 'Select a resume', selectVersion: 'Select a version', selectJob: 'Select a job', checking: 'Checking...', run: 'Run check', score: 'Health score', structure: 'Structure', coverage: 'Keyword coverage', priorities: 'Priority changes', passed: 'Passed checks', risks: 'Risks and evidence', noPriorities: 'No urgent structural changes found.', noRisks: 'No structural risks found.', selectError: 'Select a resume version and target job first.', runError: 'Unable to run the resume health check. Confirm that the selected resources belong to you.' },
    import: { eyebrow: 'Resume import', title: 'Import an Existing Resume', subtitle: 'Upload a PDF, DOCX, or TXT file up to 5 MB. The source file is parsed but not stored.', file: 'Resume file', parsing: 'Parsing...', parse: 'Parse text', fileError: 'Select a PDF, DOCX, or TXT file first.', parseError: 'Unable to parse this file. Check its format, size, and content.', reviewError: 'Review the extracted text before continuing.', stored: 'Original file stored', yes: 'yes', no: 'no', review: 'Review and correct the text before using it for generation.', extracted: 'Extracted text', use: 'Use corrected text', preview: 'Normalized input preview' },
    communication: { eyebrow: 'Communication drafts', title: 'Job communication', subtitle: 'Create editable application messages. The system never sends them to an external platform.', resume: 'Resume', version: 'Resume version', job: 'Job description', type: 'Draft type', selectResume: 'Select a resume', selectVersion: 'Select a version', selectJob: 'Select a job', cover: 'Cover letter', email: 'Email body', opening: 'Opening message', generating: 'Generating...', generate: 'Generate draft', verify: 'Verify the facts before copying or associating this draft with an application.', draft: 'Editable draft', copy: 'Copy', use: 'Use in application', selectError: 'Select a resume version and target job first.', generateError: 'Unable to generate a draft. Check AI consent and the selected resources.', copied: 'Draft copied.', clipboardError: 'Clipboard access is unavailable. Select the text and copy it manually.' },
    applications: { eyebrow: 'Application tracker', title: 'Applications', subtitle: 'Track applications you manage manually. Nothing is sent automatically.', create: 'New application', edit: 'Edit application', resume: 'Resume', version: 'Resume version', job: 'Job description', selectResume: 'Select a resume', selectVersion: 'Select a version', selectJob: 'Select a job', cover: 'Cover letter', email: 'Email body', opening: 'Opening message', saving: 'Saving...', createDraft: 'Create draft', saveChanges: 'Save changes', cancel: 'Cancel', loading: 'Loading application records...', empty: 'No application records yet.', editAction: 'Edit', delete: 'Delete', feedback: 'Feedback', saveFeedback: 'Save feedback', application: 'Application', jobRef: 'Job', versionRef: 'Resume version' },
    home: {
      eyebrow: 'ZhiLi · Build your resume among the stars', title: 'Put your resume in orbit.\nKeep every fact traceable.',
      description: 'ZhiLi is a career workspace for organizing evidence, tailoring to each job, reviewing AI drafts item by item, and exporting private PDFs. Every fact stays traceable and every record stays in your account.',
      start: 'Get started', docs: 'View docs', materials: 'Open materials', features: 'Core capabilities', featureTitle: 'Four modules, one complete career loop',
      featureDescription: 'Move from source material to export without leaving ZhiLi. Every step keeps an audit trail.',
      flow: 'Workflow', flowTitle: 'From material to PDF in five steps', flowDescription: 'Pause and resume at any step. Your workflow continues after a refresh.',
      service: 'Service status', serviceTitle: 'Services are healthy and ready', creating: 'Create account', tryJobs: 'Try the job workflow',
      statModules: 'Core modules', statSteps: 'Workflow steps', statTraceable: 'Traceable sources', statLlm: 'External LLM',
    },
  },
}

function readStoredLocale(): Locale {
  if (typeof window === 'undefined') return 'zh-CN'
  return window.localStorage.getItem(STORAGE_KEY) === 'en-US' ? 'en-US' : 'zh-CN'
}

const locale = ref<Locale>(readStoredLocale())

function applyDocumentLanguage(nextLocale: Locale) {
  if (typeof document !== 'undefined') {
    document.documentElement.lang = nextLocale === 'zh-CN' ? 'zh-CN' : 'en'
    document.title = nextLocale === 'zh-CN' ? '智历 · 岗位定制简历' : 'ZhiLi · Intelligent Resume Builder'
  }
}

applyDocumentLanguage(locale.value)

function resolve(message: MessageTree, key: string): string | undefined {
  return key.split('.').reduce<string | MessageTree | undefined>((value, part) => (
    value && typeof value === 'object' ? value[part] : undefined
  ), message) as string | undefined
}

export function useLocale() {
  function setLocale(nextLocale: Locale) {
    locale.value = nextLocale
    window.localStorage.setItem(STORAGE_KEY, nextLocale)
    applyDocumentLanguage(nextLocale)
  }

  function t(key: string) {
    return resolve(messages[locale.value], key) ?? resolve(messages['zh-CN'], key) ?? key
  }

  return { locale, setLocale, t }
}
