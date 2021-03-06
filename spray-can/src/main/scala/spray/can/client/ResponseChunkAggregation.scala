/*
 * Copyright (C) 2011-2013 spray.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package spray.can.client

import akka.util.{ ByteString, ByteStringBuilder }
import spray.http._
import spray.io._
import spray.can.Http

object ResponseChunkAggregation {

  def apply(limit: Int): PipelineStage =
    new PipelineStage {
      def apply(context: PipelineContext, commandPL: CPL, eventPL: EPL): Pipelines =
        new Pipelines with DynamicEventPipeline {
          val commandPipeline = commandPL

          val initialEventPipeline: EPL = {
            case Http.MessageEvent(ChunkedResponseStart(response)) ⇒
              if (response.entity.buffer.length <= limit) {
                val bb = ByteString.newBuilder
                bb putBytes response.entity.buffer
                eventPipeline become aggregating(response, bb)
              } else closeWithError()

            case ev ⇒ eventPL(ev)
          }

          def aggregating(response: HttpResponse, bb: ByteStringBuilder): EPL = {
            case Http.MessageEvent(MessageChunk(body, _)) ⇒
              if (bb.length + body.length <= limit) bb putBytes body
              else closeWithError()

            case Http.MessageEvent(_: ChunkedMessageEnd) ⇒
              val aggregatedEntity = response.entity.map((ct, _) ⇒ ct -> bb.result().toArray)
              eventPL(Http.MessageEvent(response.copy(entity = aggregatedEntity)))
              eventPipeline become initialEventPipeline

            case ev ⇒ eventPL(ev)
          }

          def closeWithError() {
            context.log.error("Aggregated response entity greater than configured limit of {} bytes," +
              "closing connection", limit)
            commandPL(Http.Close)
            eventPipeline become eventPL // disable this stage
          }
        }
    }
}