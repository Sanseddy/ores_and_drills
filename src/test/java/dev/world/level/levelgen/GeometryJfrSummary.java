package dev.world.level.levelgen;

import jdk.jfr.consumer.RecordingFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Report only geometry stacks and allocation weights, never environment/system-property events. */
public final class GeometryJfrSummary {
    public static void main(String[] args) throws Exception {
        Map<String,Long> samples=new HashMap<>(),allocations=new HashMap<>();
        try(var recording=new RecordingFile(Path.of(args[0]))){
            while(recording.hasMoreEvents()){
                var event=recording.readEvent();String name=event.getEventType().getName();
                if(!name.equals("jdk.ExecutionSample")&&!name.equals("jdk.ObjectAllocationSample"))continue;
                if(event.getStackTrace()==null)continue;
                var geometry=event.getStackTrace().getFrames().stream()
                        .filter(frame -> frame.getMethod().getType().getName().endsWith("CompactDepositGeometry")).findFirst();
                if(geometry.isEmpty())continue;
                if(name.equals("jdk.ExecutionSample"))samples.merge(geometry.get().getMethod().getName(),1L,Long::sum);
                else allocations.merge(event.getClass("objectClass").getName(),event.getLong("weight"),Long::sum);
            }
        }
        StringBuilder report=new StringBuilder("Geometry CPU samples (sampling, not precise per-method timing):\n");
        samples.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed()).forEach(e -> report.append(e).append('\n'));
        report.append("Sampled allocation weights (not exact allocation totals):\n");
        allocations.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed()).forEach(e -> report.append(e).append('\n'));
        Files.writeString(Path.of(args[1]),report);System.out.print(report);
    }
}
