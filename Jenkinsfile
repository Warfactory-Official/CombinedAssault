@Library('wf-ci') _

wfBuild(
    repo: 'CombinedAssault',
    upstream: ['WfLib/main'],
    jdk: '21',
    zig: true,
    branches: ['main'],
    build: 'build --no-daemon --stacktrace',
    publish: 'publish --no-daemon --stacktrace',
)
