#!/usr/bin/env groovy

properties([
  pipelineTriggers([
    triggers: [
      [
        $class: 'jenkins.triggers.ReverseBuildTrigger',
        upstreamProjects: "confluentinc/common/3.3.x", result: hudson.model.Result.SUCCESS
      ]
    ]
  ]),
])


common {
  slackChannel = '#clients-eng'
}
