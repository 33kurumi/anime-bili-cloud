/**
 * 全局 TypeScript 类型定义。
 *
 * 【设计意图】后端各微服务返回的数据结构并不统一（Anime 实体 / 搜索结果 Map / 远程 Bangumi Map），
 * 前端在这里做一层"宽字段"声明：可选字段用 ?，让同一套类型既能承接本地库数据、也能承接远程聚合数据，
 * 避免页面里到处写 any（答辩时可说明：类型边界收敛在 src/types 一处）。
 */

/** 后端统一响应体（abc-common 的 R.java） */
export interface ApiResult<T = unknown> {
  code: number
  message: string
  data: T
  timestamp?: number
}

/** 番剧（本地库 / 远程 Bangumi 聚合） */
export interface AnimeItem {
  /** 统一标识：本地为纯数字 id，远程为 "bgm:123" */
  id?: string | number
  /** 搜索结果里的标识字段（后端历史原因命名为 _id） */
  _id?: string
  bangumiId?: number | string
  title: string
  coverUrl?: string
  description?: string
  type?: string | number
  status?: string | number
  year?: number | string | null
  season?: string | null
  episodesCount?: number | null
  ratingAvg?: number | null
  /** 搜索结果里的评分字段（后端历史命名） */
  rating_avg?: number | null
  viewCount?: number | null
  studio?: string | null
  /** 数据来源：local / bangumi / db */
  _source?: string
  /** 搜索相关度 */
  _score?: number
  /** 详情接口附加：远程剧集列表 */
  remoteEpisodes?: EpisodeItem[]
  /** 详情接口附加：视频源 */
  videoSources?: VideoSourceItem[]
  /** 日历接口附加 */
  animeList?: AnimeItem[]
  day?: string
}

/** 剧集 */
export interface EpisodeItem {
  id?: number
  episodeNum: number
  title?: string | null
  duration?: number | null
}

/** 视频源（番剧详情里附带的候选源，可能为空） */
export interface VideoSourceItem {
  name?: string
  url: string
}

/** MyBatis-Plus 分页结构 */
export interface PageResult<T> {
  records: T[]
  total: number
  size: number
  current: number
  pages: number
}

/** 数据源候选（source-service 聚合排序结果） */
export interface SourceCandidate {
  ruleId?: number
  name?: string
  score?: number
  rankReason?: string
}

/** 播放信息（source-service /api/source/play 返回） */
export interface PlayInfo {
  url?: string
  proxyUrl?: string
  headers?: Record<string, string> | null
  format?: string
  sourceName?: string
  ruleId?: number
  matchedTitle?: string
  matchScore?: number
  message?: string
  candidates?: SourceCandidate[] | null
  stageUsed?: string
  /** 解析总耗时（ms） */
  elapsedMs?: number
  /** 是否命中可播放地址（后端失败时 HTTP 仍 200） */
  hit?: boolean
  /** 前端附加：当前播放地址是否走后端代理 */
  _useProxy?: boolean
}

/** 数据源（source_rule 表） */
export interface SourceRule {
  id: number
  name: string
  version?: string | null
  baseUrl?: string | null
  searchUrl?: string | null
  usePost?: number | null
  searchMode?: string | null
  chapterMode?: string | null
  searchList?: string | null
  searchName?: string | null
  searchResult?: string | null
  chapterRoads?: string | null
  chapterResult?: string | null
  searchApiCfg?: string | null
  chapterApiCfg?: string | null
  cssConfig?: string | null
  macCmsApi?: string | null
  browserResolve?: number | null
  matchVideoUrl?: string | null
  userAgent?: string | null
  referer?: string | null
  weight?: number | null
  enabled?: number | null
  healthStatus?: string | null
  successRate?: number | null
  createdAt?: string | null
  updatedAt?: string | null
}

/** 弹幕（dandanplay 导入 / 用户发送 / 时间窗接口） */
export interface DanmakuItem {
  id?: string
  text: string
  /** 出现时间（秒） */
  timestamp?: number
  /** 0=滚动 1=顶部 2=底部（后端存储字段） */
  position?: number
  color?: string
  mode?: 'SCROLL' | 'TOP' | 'BOTTOM' | string
  fontSize?: number
  userId?: number | null
  username?: string
  count?: number
  createdAt?: string
}

/** 观看历史 / 观看进度 */
export interface WatchHistoryItem {
  id?: number
  animeKey: string
  episodeNum: number
  title?: string
  coverUrl?: string
  progress?: number
  /** 时长（部分历史记录没有该字段，前端按 0 处理） */
  durationSec?: number
  watchedAt?: string
}

/** 观看进度（/api/user/progress；后端 entity 的 finished 是 0/1 整数） */
export interface ProgressItem {
  id?: number
  animeKey: string
  episodeNum: number
  positionSec: number
  durationSec?: number
  finished?: boolean | number
  device?: string
  title?: string
  coverUrl?: string
  updateTime?: string
}

/** GET /api/user/progress 的返回：{ items, lastSyncAt } */
export interface ProgressSyncResult {
  items?: ProgressItem[]
  lastSyncAt?: string
}

/** 追番/收藏（user_favorite：status 1想看/2在看/3看过/4弃番；isFollow=1 即追番） */
export interface FavoriteItem {
  id?: number
  animeKey: string
  bangumiId?: number
  title?: string
  coverUrl?: string
  status?: number
  isFollow?: number
  lastEpisode?: number
  episodeNum?: number
  createTime?: string
  updateTime?: string
}

/** 评论 */
export interface CommentItem {
  id?: number
  userId?: number
  username?: string
  animeId?: string
  episodeId?: number | null
  content: string
  parentId?: number | null
  replyToUserId?: number | null
  replyToUsername?: string | null
  likeCount?: number
  createdAt?: string
}

/** Bangumi 吐槽（/api/anime/bgm-comments） */
export interface BgmCommentItem {
  id?: number | string
  username?: string
  avatar?: string
  content: string
  rate?: number
  updatedAt?: string
}

/** 评分统计（后端实际返回 average 字符串 + distribution，远程番剧返回 avg:0/count:0） */
export interface RatingStat {
  average?: string
  avg?: number
  count?: number
  distribution?: Array<{ score: number; cnt: number }>
}

/** 用户资料 */
export interface UserProfile {
  id?: number
  userId?: number
  username: string
  nickname?: string
  email?: string
  bio?: string
  avatar?: string
  role?: string
  createTime?: string
}

/** 搜索历史项 */
export interface SearchSuggest {
  keyword: string
  count?: number
}
