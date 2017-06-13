#!/usr/bin/env groovy

properties(
  [
    [
      $class: 'RebuildSettings', autoRebuild: false, rebuildDisabled: false
    ], 
    pipelineTriggers(
      [
        upstream(threshold: 'SUCCESS', upstreamProjects: 'confluentinc/common/3.3.x')
      ]
    )
  ]
)


common {
  slackChannel = '#clients-eng'
}
