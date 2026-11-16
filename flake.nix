{
  description = "Development shell and Android SDK toolchain for idjoo/extensions";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  };

  outputs = { self, nixpkgs }:
    let
      systems = [
        "x86_64-linux"
        "aarch64-linux"
        "x86_64-darwin"
        "aarch64-darwin"
      ];
      forAllSystems = nixpkgs.lib.genAttrs systems;
    in
    {
      devShells = forAllSystems (system:
        let
          pkgs = import nixpkgs {
            inherit system;
            config = {
              allowUnfree = true;
              android_sdk.accept_license = true;
            };
          };

          androidComposition = pkgs.androidenv.composeAndroidPackages {
            platformVersions = [ "34" ];
            buildToolsVersions = [ "34.0.0" ];
          };

          baseSdkRoot = "${androidComposition.androidsdk}/libexec/android-sdk";

          arm64SdkTools = pkgs.runCommand "android-sdk-tools-static-aarch64"
            { nativeBuildInputs = [ pkgs.unzip ]; }
            ''
              mkdir -p "$out"
              unzip -q ${pkgs.fetchurl {
                url = "https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip";
                hash = "sha256-2xzqLERU1fnFqAJkay0c9WC07nutviPlGrjhiBu1D8I=";
              }} -d "$out"
              chmod +x "$out"/build-tools/* "$out"/platform-tools/*
            '';

          androidSdkRoot =
            if system == "aarch64-linux" then
              pkgs.runCommand "android-sdk-aarch64" { } ''
                mkdir -p "$out/build-tools/34.0.0"
                for item in "${baseSdkRoot}"/*; do
                  name="$(basename "$item")"
                  if [ "$name" != "build-tools" ]; then
                    ln -s "$item" "$out/$name"
                  fi
                done
                for item in "${baseSdkRoot}/build-tools/34.0.0"/*; do
                  name="$(basename "$item")"
                  case "$name" in
                    aapt|aapt2|aidl|dexdump|split-select|zipalign)
                      ln -s "${arm64SdkTools}/build-tools/$name" "$out/build-tools/34.0.0/$name"
                      ;;
                    *)
                      ln -s "$item" "$out/build-tools/34.0.0/$name"
                      ;;
                  esac
                done
              ''
            else
              baseSdkRoot;
        in
        {
          default = pkgs.mkShell ({
            packages = [
              pkgs.jdk17_headless
              pkgs.gradle
              pkgs.jq
              pkgs.imagemagick
              pkgs.nodejs
              pkgs.unzip
            ];

            JAVA_HOME = "${pkgs.jdk17_headless}";
            ANDROID_HOME = "${androidSdkRoot}";
            ANDROID_SDK_ROOT = "${androidSdkRoot}";
          } // pkgs.lib.optionalAttrs (system == "aarch64-linux") {
            AAPT2_FROM_MAVEN_OVERRIDE = "${arm64SdkTools}/build-tools/aapt2";
            GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${arm64SdkTools}/build-tools/aapt2";
          });
        });
    };
}
