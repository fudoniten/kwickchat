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
        RestrictAddressFamilies = [ "AF_INET" "AF_INET6" ];
        RestrictNamespaces = true;
        LockPersonality = true;
        SystemCallArchitectures = "native";
      };
    };

    networking.firewall.allowedTCPPorts = lib.optionals cfg.openFirewall [ cfg.port ];
  };
}
