FROM mcr.microsoft.com/devcontainers/java:17-bullseye

RUN apt-get update && export DEBIAN_FRONTEND=noninteractive \
    && apt-get -y install --no-install-recommends curl unzip zip git \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH=${PATH}:${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools

ARG CMD_LINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"

RUN mkdir -p ${ANDROID_SDK_ROOT}/cmdline-tools \
    && curl -s -L -o cmdline-tools.zip ${CMD_LINE_TOOLS_URL} \
    && unzip -q cmdline-tools.zip -d ${ANDROID_SDK_ROOT}/cmdline-tools \
    && rm cmdline-tools.zip \
    && mv ${ANDROID_SDK_ROOT}/cmdline-tools/cmdline-tools ${ANDROID_SDK_ROOT}/cmdline-tools/latest

# Accept licenses using a safer method that won't cause broken pipe errors
RUN mkdir -p ${ANDROID_SDK_ROOT}/licenses \
    && echo "24333f8a63b6825ea9c5514f83c2829b004d1fee" > ${ANDROID_SDK_ROOT}/licenses/android-sdk-license \
    && echo "84831b9409646a918e30573bab4c9c91346d8abd" > ${ANDROID_SDK_ROOT}/licenses/android-sdk-preview-license

RUN sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"

RUN chown -R vscode:vscode ${ANDROID_SDK_ROOT}
