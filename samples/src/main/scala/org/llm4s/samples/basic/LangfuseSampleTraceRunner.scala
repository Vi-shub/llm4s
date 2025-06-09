   package org.llm4s.samples.basic

   import org.llm4s.agent.LangfuseTraceExporter

   object LangfuseSampleTraceRunner {
     def main(args: Array[String]): Unit = {
       LangfuseTraceExporter.exportSampleTrace()
     }
   }