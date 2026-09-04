import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { permissionForPath, AUTH_ONLY } from '@/config/nav'
import { useRecentVisitsStore } from '@/stores/recentVisits'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/my-workbench' },

    // ===== 业务域「频道首页」（聚合页） =====
    { path: '/workbench', name: 'home-workbench', component: () => import('@/views/DomainHomeView.vue'), meta: { domain: 'workbench' } },
    { path: '/customer', name: 'home-customer', component: () => import('@/views/DomainHomeView.vue'), meta: { domain: 'customer' } },
    { path: '/store', name: 'home-store', component: () => import('@/views/DomainHomeView.vue'), meta: { domain: 'store' } },
    { path: '/marketing', name: 'home-marketing', component: () => import('@/views/DomainHomeView.vue'), meta: { domain: 'marketing' } },
    { path: '/finance', name: 'home-finance', component: () => import('@/views/DomainHomeView.vue'), meta: { domain: 'finance' } },
    { path: '/admin', name: 'home-admin', component: () => import('@/views/DomainHomeView.vue'), meta: { domain: 'admin' } },

    // ===== 已落地（新架构） =====
    { path: '/closed-loop', name: 'closed-loop', component: () => import('@/views/ClosedLoopDemoView.vue') },
    { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue'), meta: { public: true } },
    { path: '/no-auth', name: 'no-auth', component: () => import('@/views/NoAuthView.vue') },

    // ===== 一线工作台首页：全院流水牌 + 角色待办 =====
    { path: '/board', name: 'board', component: () => import('@/views/BoardView.vue') },
    { path: '/my-workbench', name: 'my-workbench', component: () => import('@/views/MyWorkbenchView.vue') },

    // ===== Phase 1 主线（按领域 store 重建） =====
    { path: '/appointment', name: 'appointment-board', component: () => import('@/views/AppointmentBoardView.vue') },
    { path: '/appointment/new', name: 'appointment-new', component: () => import('@/views/AppointmentCreateView.vue') },
    { path: '/queue', name: 'queue', component: () => import('@/views/QueueView.vue') },
    { path: '/reception', name: 'reception', component: () => import('@/views/ReceptionView.vue') },
    { path: '/guest-reg', name: 'guest-reg', component: () => import('@/views/GuestRegView.vue') },

    // ===== 客情洞察 =====
    { path: '/customer-graph', name: 'customer-graph', component: () => import('@/views/CustomerGraphView.vue') },
    { path: '/complaint', name: 'complaint', component: () => import('@/views/ComplaintView.vue') },
    { path: '/followup', name: 'followup', component: () => import('@/views/FollowupView.vue') },
    { path: '/sop', name: 'sop', component: () => import('@/views/SopManagementView.vue') },

    // ===== 交易流程 =====
    { path: '/consultation', name: 'consultation', component: () => import('@/views/ConsultationView.vue') },
    { path: '/doctor', name: 'doctor-workbench', component: () => import('@/views/DoctorWorkbenchView.vue') },
    { path: '/conversion-funnel', name: 'conversion-funnel', component: () => import('@/views/ConversionFunnelView.vue') },
    { path: '/prescription', name: 'prescription', component: () => import('@/views/PrescriptionView.vue') },
    { path: '/order', name: 'order', component: () => import('@/views/OrderView.vue') },
    { path: '/writeoff', name: 'writeoff', component: () => import('@/views/WriteoffView.vue') },
    { path: '/refund', name: 'refund', component: () => import('@/views/RefundView.vue') },
    { path: '/card-cancel', name: 'card-cancel', component: () => import('@/views/CardCancelView.vue') },

    // ===== 客户资产 =====
    { path: '/customers', name: 'customers', component: () => import('@/views/CustomerListView.vue') },
    { path: '/card-course', name: 'card-course', component: () => import('@/views/CardCourseView.vue') },
    { path: '/course-track', name: 'course-track', component: () => import('@/views/CourseTrackView.vue') },
    { path: '/asset-transfer', name: 'asset-transfer', component: () => import('@/views/TransferView.vue') },
    { path: '/contract', name: 'contract', component: () => import('@/views/ContractView.vue') },

    // ===== 病历交班 =====
    { path: '/emr', name: 'emr', component: () => import('@/views/EmrView.vue') },
    { path: '/recall', name: 'recall', component: () => import('@/views/RecallView.vue') },
    { path: '/handover', name: 'handover', component: () => import('@/views/HandoverView.vue') },

    // ===== 集团管控 M1 =====
    { path: '/m1', name: 'm1', component: () => import('@/views/M1OverviewView.vue') },
    { path: '/m1-matrix', name: 'm1-matrix', component: () => import('@/views/M1MatrixView.vue') },
    { path: '/m1-compare', name: 'm1-compare', component: () => import('@/views/M1CompareView.vue') },
    { path: '/m1-screen', name: 'm1-screen', component: () => import('@/views/M1ScreenView.vue') },
    { path: '/m1-tenant', name: 'm1-tenant', component: () => import('@/views/M1TenantView.vue') },
    { path: '/m1-region', name: 'm1-region', component: () => import('@/views/M1RegionView.vue') },
    { path: '/m1-procurement', name: 'm1-procurement', component: () => import('@/views/M1ProcurementView.vue') },
    { path: '/m1-brand', name: 'm1-brand', component: () => import('@/views/M1BrandView.vue') },
    { path: '/m1-marketing', name: 'm1-marketing', component: () => import('@/views/M1MarketingView.vue') },
    { path: '/m1-dispatch', name: 'm1-dispatch', component: () => import('@/views/M1DispatchView.vue') },
    { path: '/m1-compliance', name: 'm1-compliance', component: () => import('@/views/M1ComplianceView.vue') },
    { path: '/m1-audit-log', name: 'm1-audit-log', component: () => import('@/views/M1AuditLogView.vue') },
    { path: '/m1-health', name: 'm1-health', component: () => import('@/views/M1HealthView.vue') },
    { path: '/m1-sop', name: 'm1-sop', component: () => import('@/views/M1SopView.vue') },
    { path: '/m1-target', name: 'm1-target', component: () => import('@/views/M1TargetView.vue') },
    { path: '/m1-report', name: 'm1-report', component: () => import('@/views/M1ReportView.vue') },
    { path: '/m1-settings', name: 'settings', component: () => import('@/views/SettingsView.vue') },

    // ===== 协同中台（T3） =====
    { path: '/approval', name: 'approval', component: () => import('@/views/ApprovalView.vue') },
    { path: '/notifications', name: 'notifications', component: () => import('@/views/NotificationView.vue') },

    // ===== 门店运营 M2 =====
    { path: '/m2-inventory', name: 'm2-inventory', component: () => import('@/views/InventoryView.vue') },
    { path: '/m2-schedule', name: 'm2-schedule', component: () => import('@/views/ScheduleView.vue') },
    { path: '/m2-workorder', name: 'm2-workorder', component: () => import('@/views/WorkOrderView.vue') },
    { path: '/m2-daily', name: 'm2-daily', component: () => import('@/views/DailyView.vue') },
    // 库存生态
    { path: '/m2-requisition', name: 'm2-requisition', component: () => import('@/views/RequisitionView.vue') },
    { path: '/m2-wastage', name: 'm2-wastage', component: () => import('@/views/WastageView.vue') },
    // 空间设备
    { path: '/m2-rooms', name: 'm2-rooms', component: () => import('@/views/RoomView.vue') },
    { path: '/m2-equipment', name: 'm2-equipment', component: () => import('@/views/EquipmentView.vue') },
    // 经营
    { path: '/m2-performance', name: 'm2-performance', component: () => import('@/views/PerformanceView.vue') },
    { path: '/m2-weekly', name: 'm2-weekly', component: () => import('@/views/WeeklyView.vue') },
    // 商品价格
    { path: '/m2-pricelist', name: 'm2-pricelist', component: () => import('@/views/PricelistView.vue') },
    { path: '/m2-catalog', name: 'm2-catalog', component: () => import('@/views/CatalogView.vue') },
    { path: '/m2-projects', name: 'm2-projects', component: () => import('@/views/M2ProjectView.vue') },
    { path: '/m2-writeoff-desk', name: 'm2-writeoff-desk', component: () => import('@/views/WriteoffDeskView.vue') },
    { path: '/m2-checkin', name: 'm2-checkin', component: () => import('@/views/CheckinView.vue') },
    // 增长与异常
    { path: '/m2-inspection', name: 'm2-inspection', component: () => import('@/views/InspectionView.vue') },
    { path: '/m2-acquisition', name: 'm2-acquisition', component: () => import('@/views/AcquisitionView.vue') },
    { path: '/m2-reactivate', name: 'm2-reactivate', component: () => import('@/views/ReactivateView.vue') },
    { path: '/m2-exception', name: 'm2-exception', component: () => import('@/views/ExceptionView.vue') },
    { path: '/m2-settings', name: 'm2-settings', component: () => import('@/views/M2SettingsView.vue') },
    { path: '/m2-help', name: 'm2-help', component: () => import('@/views/HelpView.vue') },

    // ===== M3 客户资产深化 =====
    { path: '/customers/merge', name: 'customer-merge', component: () => import('@/views/CustomerMergeView.vue') },
    // 客户 360 已与客户画像合并为单页（/customers/:id），旧 /360 路由重定向到全景 360 Tab
    { path: '/customers/:id/360', redirect: (to) => ({ path: `/customers/${to.params.id}`, query: { t: '360' } }) },
    { path: '/customers/:id', name: 'customer-profile', component: () => import('@/views/CustomerProfileView.vue') },
    { path: '/m3-levels', name: 'm3-levels', component: () => import('@/views/LevelsView.vue') },
    { path: '/m3-points-mall', name: 'm3-points-mall', component: () => import('@/views/PointsMallView.vue') },
    { path: '/m3-tags', name: 'm3-tags', component: () => import('@/views/TagsView.vue') },
    { path: '/m3-journey', name: 'm3-journey', component: () => import('@/views/JourneyView.vue') },
    { path: '/m3-tasks', name: 'm3-tasks', component: () => import('@/views/FollowTasksView.vue') },
    { path: '/m3-care', name: 'm3-care', component: () => import('@/views/CareView.vue') },
    { path: '/m3-churn', name: 'm3-churn', component: () => import('@/views/ChurnView.vue') },
    { path: '/m3-referral', name: 'm3-referral', component: () => import('@/views/ReferralView.vue') },
    { path: '/m3-nps', name: 'm3-nps', component: () => import('@/views/NpsView.vue') },
    { path: '/m3-private', name: 'm3-private', component: () => import('@/views/PrivateView.vue') },
    { path: '/m3-segment', name: 'm3-segment', component: () => import('@/views/SegmentView.vue') },
    { path: '/m3-io', name: 'm3-io', component: () => import('@/views/CustomerIoView.vue') },
    { path: '/m3-risk', name: 'm3-risk', component: () => import('@/views/RiskView.vue') },
    { path: '/m3-settings', name: 'm3-settings', component: () => import('@/views/M3SettingsView.vue') },
    { path: '/m3-insight', name: 'm3-insight', component: () => import('@/views/InsightView.vue') },

    // ===== M6 数据财务（业财一体 · 红线区：只读镜像 + Outbox 对账） =====
    { path: '/m6-ledger', name: 'm6-ledger', component: () => import('@/views/FinLedgerView.vue') },
    { path: '/m6-reconcile', name: 'm6-reconcile', component: () => import('@/views/FinReconcileView.vue') },
    { path: '/m6-invoice', name: 'm6-invoice', component: () => import('@/views/FinInvoiceView.vue') },
    { path: '/m6-settlement', name: 'm6-settlement', component: () => import('@/views/FinSettlementView.vue') },
    { path: '/m6-cost', name: 'm6-cost', component: () => import('@/views/FinCostView.vue') },
    { path: '/m6-margin', name: 'm6-margin', component: () => import('@/views/FinMarginView.vue') },
    { path: '/m6-commission', name: 'm6-commission', component: () => import('@/views/FinCommissionView.vue') },
    { path: '/m6-writeoff', name: 'm6-writeoff', component: () => import('@/views/FinWriteoffView.vue') },
    { path: '/m6-prepay', name: 'm6-prepay', component: () => import('@/views/FinPrepayView.vue') },
    { path: '/m6-card-balance', name: 'm6-card-balance', component: () => import('@/views/FinCardBalanceView.vue') },
    { path: '/m6-abnormal', name: 'm6-abnormal', component: () => import('@/views/FinAbnormalView.vue') },
    { path: '/m6-tax', name: 'm6-tax', component: () => import('@/views/FinTaxView.vue') },
    { path: '/m6-cash-daily', name: 'm6-cash-daily', component: () => import('@/views/FinCashDailyView.vue') },
    { path: '/m6-monthly', name: 'm6-monthly', component: () => import('@/views/FinMonthlyView.vue') },
    { path: '/m6-budget', name: 'm6-budget', component: () => import('@/views/FinBudgetView.vue') },
    { path: '/m6-settings', name: 'm6-settings', component: () => import('@/views/FinSettingsView.vue') },

    // M5 营销中心（活动→券→触达→核销→ROI 闭环；周频≤3+违禁词合规）
    { path: '/m5-coupons', name: 'm5-coupons', component: () => import('@/views/M5CouponView.vue') },
    { path: '/m5-push', name: 'm5-push', component: () => import('@/views/M5PushView.vue') },
    { path: '/m5-poster', name: 'm5-poster', component: () => import('@/views/M5PosterView.vue') },
    { path: '/m5-live', name: 'm5-live', component: () => import('@/views/M5LiveView.vue') },
    { path: '/m5-roi', name: 'm5-roi', component: () => import('@/views/M5RoiView.vue') },
    { path: '/m5-channel', name: 'm5-channel', component: () => import('@/views/M5ChannelView.vue') },
    { path: '/m5-landing', name: 'm5-landing', component: () => import('@/views/M5LandingView.vue') },
    { path: '/m5-calendar', name: 'm5-calendar', component: () => import('@/views/M5CalendarView.vue') },
    { path: '/m5-referral', name: 'm5-referral', component: () => import('@/views/M5ReferralView.vue') },
    { path: '/m5-writeoff', name: 'm5-writeoff', component: () => import('@/views/M5WriteoffView.vue') },
    { path: '/m5-assets', name: 'm5-assets', component: () => import('@/views/M5AssetsView.vue') },
    { path: '/m5-dashboard', name: 'm5-dashboard', component: () => import('@/views/M5DashboardView.vue') },
    { path: '/m5-settings', name: 'm5-settings', component: () => import('@/views/M5SettingsView.vue') },

    // ===== Wave 5 · 四中台底座（PC only） =====
    // T1 权限中台（RBAC 真源）
    { path: '/admin/staff', name: 't1-staff', component: () => import('@/views/T1StaffView.vue') },
    { path: '/admin/roles', name: 't1-roles', component: () => import('@/views/T1RolesView.vue') },
    { path: '/admin/permissions', name: 't1-permissions', component: () => import('@/views/T1PermissionsView.vue') },
    { path: '/admin/org', name: 't1-org', component: () => import('@/views/T1OrgView.vue') },
    // T2 数据中台
    { path: '/data/collect', name: 't2-collect', component: () => import('@/views/T2CollectView.vue') },
    { path: '/data/govern', name: 't2-govern', component: () => import('@/views/T2GovernView.vue') },
    { path: '/data/tags', name: 't2-tags', component: () => import('@/views/T2TagFactoryView.vue') },
    { path: '/data/service', name: 't2-service', component: () => import('@/views/T2DataServiceView.vue') },
    // T3 流程中台补全
    { path: '/workorders', name: 't3-workorders', component: () => import('@/views/T3WorkOrderView.vue') },
    { path: '/integrations', name: 't3-integrations', component: () => import('@/views/T3IntegrationView.vue') },
    { path: '/admin/mp-settings', name: 'admin-mp-settings', component: () => import('@/views/MpSettingsView.vue') },
    { path: '/admin/dictionary', name: 'admin-dictionary', component: () => import('@/views/DictionaryView.vue') },
    { path: '/admin/dictionary/manage', name: 'admin-dictionary-manage', component: () => import('@/views/DictionaryManageView.vue') },
    // T4 AI 中台底座
    { path: '/ai/models', name: 't4-models', component: () => import('@/views/T4ModelRepoView.vue') },
    { path: '/ai/compute', name: 't4-compute', component: () => import('@/views/T4ComputeView.vue') },
    { path: '/ai/features', name: 't4-features', component: () => import('@/views/T4FeatureView.vue') },
    { path: '/ai/monitor', name: 't4-monitor', component: () => import('@/views/T4MonitorView.vue') },
    // ===== Wave 6 · A1 AI 中心 =====
    { path: '/ai', name: 'a1-home', component: () => import('@/views/A1HomeView.vue') },
    { path: '/ai/profile', name: 'a1-profile', component: () => import('@/views/A1ProfileView.vue') },
    { path: '/ai/repurchase', name: 'a1-repurchase', component: () => import('@/views/A1RepurchaseView.vue') },
    { path: '/ai/sensitive', name: 'a1-sensitive', component: () => import('@/views/A1SensitiveView.vue') },
    { path: '/ai/daily-report', name: 'a1-daily', component: () => import('@/views/A1DailyView.vue') },
    { path: '/ai/scripts', name: 'a1-scripts', component: () => import('@/views/A1ScriptsView.vue') },
    { path: '/ai/chatbot', name: 'a1-chatbot', component: () => import('@/views/A1ChatbotView.vue') },
    { path: '/ai/scheduling', name: 'a1-scheduling', component: () => import('@/views/A1SchedulingView.vue') },
    { path: '/ai/churn-model', name: 'a1-churn', component: () => import('@/views/A1ChurnView.vue') },
    { path: '/ai/content', name: 'a1-content', component: () => import('@/views/A1ContentView.vue') },
    { path: '/ai/knowledge', name: 'a1-knowledge', component: () => import('@/views/A1KnowledgeView.vue') },
    { path: '/ai/govern', name: 'a1-govern', component: () => import('@/views/A1GovernView.vue') },
    { path: '/ai/privacy', name: 'a1-privacy', component: () => import('@/views/A1PrivacyView.vue') },
    { path: '/ai/gateway', name: 'a1-gateway', component: () => import('@/views/A1GatewayView.vue') },
    { path: '/ai/admin', name: 'a1-admin', component: () => import('@/views/A1AdminView.vue') },

    // ===== Wave 7 · G 通用收尾 =====
    { path: '/search', name: 'g-search', component: () => import('@/views/GSearchView.vue') },
    { path: '/help', name: 'g-help', component: () => import('@/views/GHelpView.vue') },
    { path: '/notif-settings', name: 'g-notif-settings', component: () => import('@/views/GNotifSettingsView.vue') },
    { path: '/theme', name: 'g-theme', component: () => import('@/views/GThemeView.vue') },
    { path: '/about', name: 'g-about', component: () => import('@/views/GAboutView.vue') },
    { path: '/guide', name: 'g-guide', component: () => import('@/views/GGuideView.vue') },
    { path: '/profile', name: 'g-profile', component: () => import('@/views/GProfileView.vue') },

    // ===== Wave 7 · C 端小程序（独立手机壳，不经过 B 端 RBAC）=====
    { path: '/m', name: 'c-home', component: () => import('@/views/mobile/MHomeView.vue'), meta: { mTitle: '美研云' } },
    { path: '/m/projects', name: 'c-projects', component: () => import('@/views/mobile/MProjectListView.vue'), meta: { mTitle: '全部项目', mBack: true } },
    { path: '/m/project/:id', name: 'c-project-detail', component: () => import('@/views/mobile/MProjectDetailView.vue'), meta: { mTitle: '项目详情', mBack: true } },
    { path: '/m/project/:id/buy', name: 'c-project-buy', component: () => import('@/views/mobile/MProjectBuyView.vue'), meta: { mTitle: '确认订单', mBack: true } },
    { path: '/m/booking', name: 'c-booking', component: () => import('@/views/mobile/MBookingView.vue'), meta: { mTitle: '我的预约', mBack: true } },
    { path: '/m/booking/new', name: 'c-booking-new', component: () => import('@/views/mobile/MBookingNewView.vue'), meta: { mTitle: '新建预约', mBack: true } },
    { path: '/m/me', name: 'c-me', component: () => import('@/views/mobile/MMeView.vue'), meta: { mTitle: '我的' } },
    { path: '/m/stores', name: 'c-stores', component: () => import('@/views/mobile/MStoresView.vue'), meta: { mTitle: '附近门店', mBack: true } },
    { path: '/m/store/:id', name: 'c-store-detail', component: () => import('@/views/mobile/MStoreDetailView.vue'), meta: { mTitle: '门店详情', mBack: true } },
    { path: '/m/packages', name: 'c-packages', component: () => import('@/views/mobile/MPackagesView.vue'), meta: { mTitle: '我的套餐', mBack: true } },
    { path: '/m/records', name: 'c-records', component: () => import('@/views/mobile/MRecordsView.vue'), meta: { mTitle: '消费记录', mBack: true } },
    { path: '/m/orders', name: 'c-orders', component: () => import('@/views/mobile/MOrdersView.vue'), meta: { mTitle: '我的订单', mBack: true } },
    { path: '/m/order/:id', name: 'c-order-detail', component: () => import('@/views/mobile/MOrderDetailView.vue'), meta: { mTitle: '订单详情', mBack: true } },
    { path: '/m/advisor', name: 'c-advisor', component: () => import('@/views/mobile/MAdvisorView.vue'), meta: { mTitle: '专属顾问', mBack: true } },
    { path: '/m/invite', name: 'c-invite', component: () => import('@/views/mobile/MInviteView.vue'), meta: { mTitle: '邀请有礼', mBack: true } },
    { path: '/m/settings', name: 'c-settings', component: () => import('@/views/mobile/MSettingsView.vue'), meta: { mTitle: '设置', mBack: true } },
    { path: '/m/coupons', name: 'c-coupons', component: () => import('@/views/mobile/MCouponsView.vue'), meta: { mTitle: '优惠券', mBack: true } },
    { path: '/m/card', name: 'c-card', component: () => import('@/views/mobile/MCardView.vue'), meta: { mTitle: '我的会员卡', mBack: true } },
    { path: '/m/points-mall', name: 'c-points-mall', component: () => import('@/views/mobile/MPointsMallView.vue'), meta: { mTitle: '积分商城', mBack: true } },
    { path: '/m/receipt/:id', name: 'c-receipt', component: () => import('@/views/mobile/MReceiptView.vue'), meta: { mTitle: '电子小票', mBack: true } },
    { path: '/m/followup', name: 'c-followup', component: () => import('@/views/mobile/MFollowupView.vue'), meta: { mTitle: '术后回访', mBack: true } },
    { path: '/m/notifications', name: 'c-notifications', component: () => import('@/views/mobile/MNotificationsView.vue'), meta: { mTitle: '消息中心', mBack: true } },

    // 404（必须放在最后）
    { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('@/views/NotFoundView.vue') },
  ],
})

// ============================================================
// 权限守卫（产品架构基线落地）
// 任何路由所需权限由 config/nav.ts 的 permissionForPath 推导，与菜单同源。
// ============================================================
router.beforeEach((to) => {
  // C 端小程序路由（/m 前缀）走独立会员体系，不经过 B 端 RBAC
  if (to.path === '/m' || to.path.startsWith('/m/')) return true
  const auth = useAuthStore()
  // 已登录用户访问登录页：直接进工作台
  if (to.path === '/login') {
    if (auth.isAuthenticated) return { path: '/my-workbench' }
    return true
  }
  const need = permissionForPath(to.path)
  // 未登录（无会话且无 ?as= 离线演示角色）→ 登录页，携带回跳地址
  const hasIdentity = auth.isAuthenticated
    || (!!auth.currentRoles && auth.currentRoles.length > 0)
  if (!hasIdentity) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  // 已登录但权限不足 → 403 页
  if (need && need !== AUTH_ONLY && !auth.can(need)) {
    return { path: '/no-auth', query: { from: to.fullPath, need } }
  }
  return true
})

// 记录页面访问历史
router.afterEach((to) => {
  if (to.path === '/m' || to.path.startsWith('/m/')) return
  const recent = useRecentVisitsStore()
  recent.addVisit(to.path)
})

export default router
