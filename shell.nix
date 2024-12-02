
{ pkgs ? import <nixpkgs> {} }:

let
  # Version, URL and hash of the Spark binary
  sparkVersion = "3.5.0";
  sparkUrl = "https://archive.apache.org/dist/spark/spark-${sparkVersion}/spark-${sparkVersion}-bin-hadoop3.tgz";
  sparkHash = "8883c67e0a138069e597f3e7d4edbbd5c3a565d50b28644aad02856a1ec1da7cb92b8f80454ca427118f69459ea326eaa073cf7b1a860c3b796f4b07c2101319";

  # Derivation for preparing the Spark binary
  spark = pkgs.stdenv.mkDerivation {
    pname = "spark";
    version = sparkVersion;

    # Fetch the tarball
    src = pkgs.fetchurl {
      url = sparkUrl;
      sha512 = sparkHash;
    };
    # Install the tarball on the system, it will be located /nix/store/...
    installPhase = ''
      mkdir -p $out
      tar -xzf $src --strip-components=1 -C $out
    '';
    # Define the metadata of the derivation, not relevant for the build
    meta = {
      description = "Apache Spark ${sparkVersion} with prebuilt Hadoop3 binaries";
      licenses= pkgs.licenses.apache2;
      homepage = "https://spark.apache.org";
    };
  };
in

# Define the develpment shell that includes Spark, sbt and Zulu8 JDK
pkgs.mkShell {
  packages = [
    # Packages from nixpkgs (https://search.nixos.org/packages)
    pkgs.zulu8
    pkgs.sbt
    # Spark binary fetched from the official Apache archive
    spark
   ]; 

  # Configure the environment variables
  SPARK_HOME = "${spark.out}";

  # Script to be executed when the shell is started
  shellHook = ''
    echo "Your development environment for qbeast is ready, happy coding!"
    echo "Try 'spark-shell' or 'sbt test' to start."
  '';
}
