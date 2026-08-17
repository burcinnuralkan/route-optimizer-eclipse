package com.hitit.aviation.core;

import java.util.List;

import com.hitit.aviation.core.data.ScheduleLoader;
import com.hitit.aviation.core.emission.EmissionCalculator;
import com.hitit.aviation.core.finance.ProfitCalculator;
import com.hitit.aviation.core.graph.RouteOptimizer;
import com.hitit.aviation.core.model.FlightEvaluation;
import com.hitit.aviation.core.model.OptimizerParams;
import com.hitit.aviation.core.model.RouteResult;
import com.hitit.aviation.core.model.ScoredFlight;

public class Demo {

    public static void main(String[] args) throws Exception {

        String from = args.length > 0 ? args[0] : "ESB";
        String to = args.length > 1 ? args[1] : "JFK";

        ScheduleLoader data = ScheduleLoader.loadSampleData();

        RouteOptimizer optimizer =
                new RouteOptimizer(
                        new ProfitCalculator(
                                new EmissionCalculator()));

        System.out.println(
                "=== " + from + " -> " + to
                        + " | " + data.flights().size()
                        + " uçuşluk tarife ===");

        for (OptimizerParams.Objective obj : OptimizerParams.Objective.values()) {

            OptimizerParams p = OptimizerParams.builder()
                    .objective(obj)
                    .build();

            List<RouteResult> routes =
                    optimizer.bestRoutesPerState(
                            data.flights(),
                            from,
                            to,
                            p);

            System.out.println("\nAmaç: " + obj);

            if (routes.isEmpty()) {
                System.out.println("Rota bulunamadı");
                continue;
            }

            printRoute(routes.get(0));
        }

        System.out.println("\nSAF etkisi (%30 harman, WEIGHTED)");

        OptimizerParams safParams = OptimizerParams.builder()
                .objective(OptimizerParams.Objective.WEIGHTED)
                .fuel(f -> f.safBlendRatio(0.30))
                .build();

        List<RouteResult> safRoutes =
                optimizer.bestRoutesPerState(
                        data.flights(),
                        from,
                        to,
                        safParams);

        if (!safRoutes.isEmpty()) {
            printRoute(safRoutes.get(0));
        }
    }

    private static void printRoute(RouteResult r) {

        System.out.println("Rota: " + r.pathString());

        for (ScoredFlight leg : r.legs()) {

            FlightEvaluation e = leg.evaluation();

            var f = e.flight();

            System.out.printf(
                    "%-7s %-7s %s->%s | %4.0f km | Kar $%,.0f | CO2 %.1f t | BELF %.0f%%%n",
                    f.flightNo(),
                    f.tailNumber(),
                    f.from().code(),
                    f.to().code(),
                    f.distanceKm(),
                    e.profitUsd(),
                    e.co2Kg() / 1000.0,
                    e.breakEvenLoadFactor() * 100
            );
        }

        System.out.printf(
                "Toplam Gelir : $%,.0f%n" +
                "Toplam Maliyet : $%,.0f%n" +
                "Toplam Kar : $%,.0f%n" +
                "Toplam CO2 : %.1f ton%n",
                r.totalRevenueUsd(),
                r.totalCostUsd(),
                r.totalProfitUsd(),
                r.totalCo2Kg() / 1000.0
        );
    }
}