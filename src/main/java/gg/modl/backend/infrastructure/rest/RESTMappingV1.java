package gg.modl.backend.infrastructure.rest;

public final class RESTMappingV1 {
    private static final String V1 = "/v1";
    public static final String PREFIX_PANEL = V1 + "/panel";
    public static final String PREFIX_ADMIN = V1 + "/admin";
    public static final String ADMIN_AUTH = PREFIX_ADMIN + "/auth";
    public static final String PREFIX_PUBLIC = V1 + "/public";
    public static final String PREFIX_MINECRAFT = V1 + "/minecraft";
    public static final String PREFIX_REPLAY_LITE = V1 + "/replay-lite";
    public static final String HEALTH = V1 + "/health";

    private static final String SERVER = "/server";
    public static final String PUBLIC_SERVER = PREFIX_PUBLIC + SERVER;
    public static final String PANEL_SERVER = PREFIX_PANEL + SERVER;

    private static final String PLAYERS = "/players";
    public static final String PANEL_PLAYERS = PREFIX_PANEL + PLAYERS;
    public static final String PUBLIC_PLAYERS = PREFIX_PUBLIC + PLAYERS;
    public static final String MINECRAFT_PLAYERS = PREFIX_MINECRAFT + PLAYERS;

    private static final String AUTH = "/auth";
    public static final String PANEL_AUTH = PREFIX_PANEL + AUTH;

    private static final String SETTINGS = "/settings";
    public static final String PANEL_SETTINGS = PREFIX_PANEL + SETTINGS;
    public static final String PUBLIC_SETTINGS = PREFIX_PUBLIC + SETTINGS;

    private static final String TICKETS = "/tickets";
    public static final String PANEL_TICKETS = PREFIX_PANEL + TICKETS;
    public static final String PUBLIC_TICKETS = PREFIX_PUBLIC + TICKETS;
    public static final String MINECRAFT_TICKETS = PREFIX_MINECRAFT + TICKETS;
    private static final String TICKET_SUBSCRIPTIONS = "/ticket-subscriptions";
    public static final String PANEL_TICKET_SUBSCRIPTIONS = PREFIX_PANEL + TICKET_SUBSCRIPTIONS;

    private static final String STAFF = "/staff";
    public static final String PANEL_STAFF = PREFIX_PANEL + STAFF;
    public static final String MINECRAFT_STAFF = PREFIX_MINECRAFT + STAFF;
    private static final String ROLES = "/roles";
    public static final String PANEL_ROLES = PREFIX_PANEL + ROLES;
    public static final String MINECRAFT_ROLES = PREFIX_MINECRAFT + ROLES;

    private static final String ANALYTICS = "/analytics";
    public static final String PANEL_ANALYTICS = PREFIX_PANEL + ANALYTICS;
    public static final String ADMIN_ANALYTICS = PREFIX_ADMIN + ANALYTICS;
    private static final String AUDIT = "/audit";
    public static final String PANEL_AUDIT = PREFIX_PANEL + AUDIT;
    private static final String DASHBOARD = "/dashboard";
    public static final String PANEL_DASHBOARD = PREFIX_PANEL + DASHBOARD;
    public static final String MINECRAFT_DASHBOARD = PREFIX_MINECRAFT + DASHBOARD;

    private static final String BILLING = "/billing";
    public static final String PANEL_BILLING = PREFIX_PANEL + BILLING;
    private static final String MEDIA = "/media";
    public static final String PANEL_MEDIA = PREFIX_PANEL + MEDIA;
    public static final String PUBLIC_MEDIA = PREFIX_PUBLIC + MEDIA;
    private static final String STORAGE = "/storage";
    public static final String PANEL_STORAGE = PREFIX_PANEL + STORAGE;

    private static final String KNOWLEDGEBASE = "/knowledgebase";
    public static final String PANEL_KNOWLEDGEBASE = PREFIX_PANEL + KNOWLEDGEBASE;
    public static final String PUBLIC_KNOWLEDGEBASE = PREFIX_PUBLIC + KNOWLEDGEBASE;
    private static final String HOMEPAGE_CARDS = "/homepage-cards";
    public static final String PANEL_HOMEPAGE_CARDS = PREFIX_PANEL + HOMEPAGE_CARDS;
    public static final String PUBLIC_HOMEPAGE_CARDS = PREFIX_PUBLIC + HOMEPAGE_CARDS;

    private static final String WEBHOOKS = "/webhooks";
    public static final String WEBHOOKS_STRIPE = V1 + WEBHOOKS + "/stripe";

    private static final String APPEALS = "/appeals";
    public static final String PANEL_APPEALS = PREFIX_PANEL + APPEALS;
    public static final String PUBLIC_APPEALS = PREFIX_PUBLIC + APPEALS;

    private static final String REGISTRATION = "/registration";
    public static final String PUBLIC_REGISTRATION = PREFIX_PUBLIC + REGISTRATION;

    private static final String LOGS = "/logs";
    public static final String PANEL_LOGS = PREFIX_PANEL + LOGS;

    private static final String MIGRATION = "/migration";
    public static final String PANEL_MIGRATION = PREFIX_PANEL + MIGRATION;
    public static final String MINECRAFT_MIGRATION = PREFIX_MINECRAFT + MIGRATION;

    private static final String PUNISHMENTS = "/punishments";
    public static final String MINECRAFT_PUNISHMENTS = PREFIX_MINECRAFT + PUNISHMENTS;
    private static final String PUNISHMENT = "/punishment";
    public static final String PUBLIC_PUNISHMENT = PREFIX_PUBLIC + PUNISHMENT;
    private static final String NOTIFICATIONS = "/notifications";
    public static final String MINECRAFT_NOTIFICATIONS = PREFIX_MINECRAFT + NOTIFICATIONS;
    private static final String REPORTS = "/reports";
    public static final String MINECRAFT_REPORTS = PREFIX_MINECRAFT + REPORTS;

    // Evidence upload uses public, token-based auth instead of the normal session/api-key flow.
    private static final String EVIDENCE_UPLOAD = "/evidence-upload";
    public static final String PUBLIC_EVIDENCE_UPLOAD = PREFIX_PUBLIC + EVIDENCE_UPLOAD;
    private static final String SERVERS = "/servers";
    public static final String ADMIN_SERVERS = PREFIX_ADMIN + SERVERS;
    private static final String MONITORING = "/monitoring";
    public static final String ADMIN_MONITORING = PREFIX_ADMIN + MONITORING;

    private static final String SYSTEM = "/system";
    public static final String ADMIN_SYSTEM = PREFIX_ADMIN + SYSTEM;

    private static final String SECURITY = "/security";
    public static final String ADMIN_SECURITY = PREFIX_ADMIN + SECURITY;

    private static final String ALERTS = "/alerts";
    public static final String ADMIN_ALERTS = PREFIX_ADMIN + ALERTS;

    private static final String REALTIME = "/realtime";
    public static final String REALTIME_WS = V1 + REALTIME + "/ws";

    private static final String REPLAYS = "/replays";
    public static final String MINECRAFT_REPLAYS = PREFIX_MINECRAFT + REPLAYS;
    public static final String PUBLIC_REPLAYS = PREFIX_PUBLIC + REPLAYS;
    public static final String REPLAY_LITE_REPLAYS = PREFIX_REPLAY_LITE + REPLAYS;
    public static final String PUBLIC_REPLAY_LITE_REPLAYS = PREFIX_PUBLIC + "/replay-lite" + REPLAYS;

    private RESTMappingV1() {
        throw new UnsupportedOperationException();
    }
}
