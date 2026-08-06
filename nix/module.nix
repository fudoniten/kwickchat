self:
{ config, lib, pkgs, ... }:

let
  cfg = config.services.kwickchat;
in
{
  options.services.kwickchat = {
    enable = lib.mkEnableOption "the kwickchat chat server";

    package = lib.mkOption {
      type = lib.types.package;
      default = self.packages.${pkgs.stdenv.hostPlatform.system}.default;
      defaultText = lib.literalExpression "kwickchat.packages.\${system}.default";
      description = "The kwickchat package to run.";
    };

    host = lib.mkOption {
      type = lib.types.str;
      default = "127.0.0.1";
      description = ''
        Address to bind. Defaults to localhost; put a TLS-terminating reverse
        proxy (nginx, Caddy, …) in front for public access.
      '';
    };

    port = lib.mkOption {
      type = lib.types.port;
      default = 5660;
      description = "TCP port to listen on.";
    };

    state-directory = lib.mkOption {
      type = lib.types.path;
      default = "/var/lib/kwickchat";
      description = ''
        Directory holding all persistent data (the SQLite database). Must be on
        persistent storage. It is created on activation, owned by the service
        user, so it may live anywhere the host keeps state (e.g. /state/...).
      '';
    };

    public-url = lib.mkOption {
      type = lib.types.nullOr lib.types.str;
      default = null;
      example = "https://chat.example.com";
      description = ''
        Public URL this kwickchat is reachable at, without a trailing slash.
        Only used to make away notifications tappable — without it they still
        arrive, they just don't link back to the room.
      '';
    };

    ntfy-server = lib.mkOption {
      type = lib.types.str;
      default = "https://ntfy.sh";
      example = "https://ntfy.example.com";
      description = ''
        ntfy server used for away notifications. Members pick their own topic
        from the web UI; the server is chosen here so kwickchat can't be talked
        into POSTing to an arbitrary host. Set to "" to switch the feature off
        entirely — the button then disappears from the UI.

        Nothing is sent anywhere until a member opts in and registers a topic.
      '';
    };

    user = lib.mkOption {
      type = lib.types.str;
      default = "kwickchat";
      description = "User account under which the server runs and owns its state.";
    };

    group = lib.mkOption {
      type = lib.types.str;
      default = "kwickchat";
      description = "Group under which the server runs.";
    };

    openFirewall = lib.mkOption {
      type = lib.types.bool;
      default = false;
      description = "Whether to open the listen port in the firewall.";
    };
  };

  config = lib.mkIf cfg.enable {
    # A stable system user so the state directory keeps consistent ownership
    # across restarts — a dynamic user can't own persistent state on /state.
    users.users = lib.mkIf (cfg.user == "kwickchat") {
      kwickchat = {
        isSystemUser = true;
        group = cfg.group;
        description = "kwickchat chat server";
      };
    };
    users.groups = lib.mkIf (cfg.group == "kwickchat") {
      kwickchat = { };
    };

    # Create the state directory (and any parents) owned by the service user.
    systemd.tmpfiles.rules = [
      "d ${cfg.state-directory} 0750 ${cfg.user} ${cfg.group} - -"
    ];

    systemd.services.kwickchat = {
      description = "kwickchat chat server";
      wantedBy = [ "multi-user.target" ];
      after = [ "network.target" ];

      environment = {
        KWICKCHAT_HOST = cfg.host;
        KWICKCHAT_PORT = toString cfg.port;
        KWICKCHAT_DIR = cfg.state-directory;
        KWICKCHAT_NTFY = cfg.ntfy-server;
      } // lib.optionalAttrs (cfg.public-url != null) {
        KWICKCHAT_URL = cfg.public-url;
      };

      serviceConfig = {
        ExecStart = lib.getExe cfg.package;
        Restart = "on-failure";
        RestartSec = 2;

        User = cfg.user;
        Group = cfg.group;
        WorkingDirectory = cfg.state-directory;

        # Hardening
        NoNewPrivileges = true;
        ProtectSystem = "strict";
        ProtectHome = true;
        PrivateTmp = true;
        PrivateDevices = true;
        ReadWritePaths = [ cfg.state-directory ];
        # AF_UNIX is needed for name resolution via nscd/systemd-resolved,
        # which outgoing ntfy notifications depend on.
        RestrictAddressFamilies = [ "AF_UNIX" "AF_INET" "AF_INET6" ];
        RestrictNamespaces = true;
        LockPersonality = true;
        SystemCallArchitectures = "native";
      };
    };

    networking.firewall.allowedTCPPorts = lib.optionals cfg.openFirewall [ cfg.port ];
  };
}
