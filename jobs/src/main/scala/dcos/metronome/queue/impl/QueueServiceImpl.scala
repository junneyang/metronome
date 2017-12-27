package dcos.metronome.queue.impl

import dcos.metronome.queue.QueueService
import mesosphere.marathon.core.launchqueue.LaunchQueue

class QueueServiceImpl(launchQueue: LaunchQueue) extends QueueService {

  override def list(): Iterable[LaunchQueue.QueuedTaskInfo] = {
    launchQueue.list
  }
}