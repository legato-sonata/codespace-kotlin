FROM mcr.microsoft.com/devcontainers/java:17-bullseye

# Install required tools
RUN apt-get update && export DEBIAN_FRONTEND=noninteractive \
    && apt-get -y install --no-install-recommends \
        curl \
        unzip \
        zip \
        git \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

# Set up Android SDK Environment Variables
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH=${PATH}:${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools

# Download and install Android Command Line Tools
# You can check for newer versions at https://developer.android.com/studio#command-tools
ARG CMD_LINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"

RUN mkdir -p ${ANDROID_SDK_ROOT}/cmdline-tools \
    && curl -s -L -o cmdline-tools.zip ${CMD_LINE_TOOLS_URL} \
    && unzip -q cmdline-tools.zip -d ${ANDROID_SDK_ROOT}/cmdline-tools \
    && rm cmdline-tools.zip \
    && mv ${ANDROID_SDK_ROOT}/cmdline-tools/cmdline-tools ${ANDROID_SDK_ROOT}/cmdline-tools/latest

# Accept Android licenses and install essential SDK components
RUN yes | sdkmanager --licenses \
    && sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"

# Change ownership of the Android SDK so the default 'vscode' user can use/update it
RUN chown -R vscode:vscode ${ANDROID_SDK_ROOT}
