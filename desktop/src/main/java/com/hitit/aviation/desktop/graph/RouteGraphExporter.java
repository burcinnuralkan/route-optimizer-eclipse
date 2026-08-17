package com.hitit.aviation.desktop.graph;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.hitit.aviation.core.model.ScoredFlight;
import com.hitit.aviation.core.model.Flight;
import com.hitit.aviation.core.model.FlightDecision;
import com.hitit.aviation.core.model.RouteResult;
import com.hitit.aviation.desktop.ui.Decisions;

/**
 * Tarifeyi Graphviz DOT grafiğine çevirir.
 *
 * Okunabilirlik için: aynı şehir çifti arasındaki uçuşlar TEK kenarda
 * birleştirilir ("3 uçuş"), rota dışı kenarlar soluk çizilir, seçili rotanın
 * bacakları sıra numarası + saat + mesafe ile vurgulanır. {@code onlyRoute}
 * ile tarifenin geri kalanı tamamen gizlenebilir.
 */
public class RouteGraphExporter {

    private static final String ROUTE_COLOR = "#c62828";   // seçili rota
    private static final String DIM_COLOR = "#c8ccd0";     // diğer uçuşlar
    private static final String DIM_FONT = "#9aa0a6";

    public String toDot(List<Flight> flights, RouteResult selected) {
       return toDot(flights, selected, false);
    }

    /**
     * Rota seçilmeden TÜM AĞI çizer: her havalimanı bir düğüm, her hat (kalkış-varış
     * çifti) tek bir kenar. Kenarın rengi o hattın TOPLAM katkı payına göre verilen
     * karardır (yeşil=UÇUR, sarı=İNCELE, kırmızı=İPTAL), kalınlığı ise hattaki uçuş
     * sayısıyla artar. Böylece "ağın neresi para kaybettiriyor" tek bakışta görülür.
     *
     * <p>Karar HAT bazındadır, uçuş bazında değil: aynı hatta biri kâr biri zarar
     * eden iki uçuş varsa kenar toplamı yansıtır. Uçuş kırılımı kenar etiketindedir.
     */
    public String toDotFleet(List<ScoredFlight> fleet, double band) {

       Map<String, Leg> legs = new LinkedHashMap<>();
       Map<String, Integer> touches = new LinkedHashMap<>();   // havalimanı -> uğrayan uçuş sayısı

       for (ScoredFlight s : fleet) {
          Flight f = s.flight();
          String from = f.from().code();
          String to = f.to().code();
          legs.computeIfAbsent(from + "|" + to, k -> new Leg(from, to))
             .add(s.evaluation().contributionMarginUsd());
          touches.merge(from, 1, Integer::sum);
          touches.merge(to, 1, Integer::sum);
       }

       int fly = 0, review = 0, cancel = 0;
       for (Leg l : legs.values()) {
          switch (FlightDecision.of(l.cm, band)) {
             case FLY -> fly++;
             case REVIEW -> review++;
             case CANCEL -> cancel++;
          }
       }

       StringBuilder sb = new StringBuilder();
       sb.append("digraph Fleet {\n");
       sb.append("  graph [rankdir=LR, splines=true, overlap=false, pad=0.3,\n")
         .append("         nodesep=0.25, ranksep=1.1, fontname=\"Helvetica\", fontsize=15,\n")
         .append("         labelloc=\"t\", label=").append(quote(String.format(Locale.US,
               "Tüm tarife — %d uçuş, %d hat   |   hat kararı: UÇUR %d · İNCELE %d · İPTAL %d   (band ±$%,.0f)",
               fleet.size(), legs.size(), fly, review, cancel, band))).append("];\n");
       sb.append("  node [shape=box, style=\"rounded,filled\", fillcolor=\"#f4f5f7\",\n")
         .append("        color=\"#d0d4d9\", fontname=\"Helvetica\", fontsize=11, margin=\"0.14,0.07\"];\n");
       sb.append("  edge [fontname=\"Helvetica\", fontsize=9, arrowsize=0.7];\n\n");

       // Yoğun havalimanları (aktarma merkezleri) daha büyük/vurgulu çizilir.
       for (Map.Entry<String, Integer> t : touches.entrySet()) {
          boolean hub = t.getValue() >= HUB_MIN_TOUCHES;
          sb.append("  ").append(quote(t.getKey()))
            .append(" [label=").append(quote(t.getKey() + "\n" + t.getValue() + " uçuş"));
          if (hub) sb.append(", fillcolor=\"#e8eaf6\", color=\"#5c6bc0\", penwidth=2, fontsize=13");
          sb.append("];\n");
       }
       sb.append('\n');

       for (Leg l : legs.values()) {
          String color = decisionColor(FlightDecision.of(l.cm, band));
          sb.append("  ").append(quote(l.from)).append(" -> ").append(quote(l.to))
            .append(" [label=").append(quote(String.format(Locale.US,
                  "%d uçuş · $%,.0f", l.count, l.cm)))
            .append(", color=\"").append(color).append("\", fontcolor=\"").append(color)
            .append("\", penwidth=").append(String.format(Locale.US, "%.1f",
                  Math.min(4.5, 1.2 + 0.6 * l.count)))
            .append("];\n");
       }

       sb.append("}\n");
       return sb.toString();
    }

    /** Bir hattın (kalkış-varış çifti) toplamı. */
    private static final class Leg {
       final String from, to;
       int count;
       double cm;
       Leg(String from, String to) { this.from = from; this.to = to; }
       void add(double contributionMarginUsd) { count++; cm += contributionMarginUsd; }
    }

    /** Bu kadar uçuş uğrayan havalimanı "merkez" sayılır ve vurgulu çizilir. */
    private static final int HUB_MIN_TOUCHES = 8;

    /** Karar renkleri tabloyla/grafiklerle aynı olsun diye {@link Decisions}'tan gelir. */
    private static String decisionColor(FlightDecision d) {
       return Decisions.barColor(d);
    }

    public String toDot(List<Flight> flights, RouteResult selected, boolean onlyRoute) {

       // Seçili rotanın bacakları: uçuş -> kaçıncı bacak (1'den başlar).
       Map<Flight, Integer> routeLegs = new IdentityHashMap<>();
       if (selected != null) {
          int i = 1;
          for (ScoredFlight e : selected.legs()) {
             routeLegs.put(e.flight(), i++);
          }
       }

       StringBuilder sb = new StringBuilder();

       sb.append("digraph Routes {\n");
       sb.append("  graph [rankdir=LR, splines=true, overlap=false, pad=0.3,\n")
         .append("         nodesep=0.22, ranksep=1.0, fontname=\"Helvetica\", fontsize=15,\n")
         .append("         labelloc=\"t\", label=").append(quote(title(selected))).append("];\n");
       sb.append("  node [shape=box, style=\"rounded,filled\", fillcolor=\"#f4f5f7\",\n")
         .append("        color=\"#d0d4d9\", fontname=\"Helvetica\", fontsize=11, margin=\"0.14,0.07\"];\n");
       sb.append("  edge [fontname=\"Helvetica\", fontsize=9, arrowsize=0.6,\n")
         .append("        color=\"").append(DIM_COLOR).append("\", fontcolor=\"").append(DIM_FONT).append("\"];\n\n");

       appendRouteNodes(sb, selected);
       appendEdges(sb, flights, routeLegs, onlyRoute);

       sb.append("}\n");

       return sb.toString();
    }

    /** Rotadaki havalimanlarını rolüne göre renklendirir (kalkış/aktarma/varış). */
    private static void appendRouteNodes(StringBuilder sb, RouteResult selected) {

       if (selected == null || selected.legs().isEmpty()) return;

       List<String> path = new ArrayList<>();
       path.add(selected.legs().get(0).flight().from().code());
       for (ScoredFlight e : selected.legs()) {
          path.add(e.flight().to().code());
       }

       for (int i = 0; i < path.size(); i++) {

          String role;
          String fill;
          String border;

          if (i == 0) {
             role = "kalkış";  fill = "#e3f2e1"; border = "#4a934a";
          } else if (i == path.size() - 1) {
             role = "varış";   fill = "#fde5e5"; border = ROUTE_COLOR;
          } else {
             role = "aktarma"; fill = "#fff4d6"; border = "#c98a00";
          }

          sb.append("  ").append(quote(path.get(i)))
            .append(" [label=").append(quote(path.get(i) + "\n" + role))
            .append(", fillcolor=\"").append(fill).append("\", color=\"").append(border)
            .append("\", penwidth=2, fontsize=12];\n");
       }
       sb.append('\n');
    }

    /** Aynı şehir çiftini tek kenarda toplar; rota bacakları ayrı ve vurgulu çizilir. */
    private static void appendEdges(StringBuilder sb, List<Flight> flights,
          Map<Flight, Integer> routeLegs, boolean onlyRoute) {

       // Rota dışı uçuşlar: "IST|JFK" -> o çiftteki uçuşlar
       Map<String, List<Flight>> grouped = new LinkedHashMap<>();

       for (Flight f : flights) {

          Integer leg = routeLegs.get(f);

          if (leg != null) {
             sb.append("  ").append(quote(f.from().code()))
               .append(" -> ").append(quote(f.to().code()))
               .append(" [label=").append(quote(routeLegLabel(leg, f)))
               .append(", color=\"").append(ROUTE_COLOR).append("\", fontcolor=\"").append(ROUTE_COLOR)
               .append("\", penwidth=2.6, fontsize=11, arrowsize=0.9, weight=10];\n");
             continue;
          }

          if (!onlyRoute) {
             grouped.computeIfAbsent(f.from().code() + "|" + f.to().code(),
                   k -> new ArrayList<>()).add(f);
          }
       }

       if (grouped.isEmpty()) return;

       sb.append('\n');

       for (Map.Entry<String, List<Flight>> e : grouped.entrySet()) {

          String[] pair = e.getKey().split("\\|");
          List<Flight> group = e.getValue();

          // Tek uçuşsa numarasını, birden fazlaysa sayısını yaz.
          String label = group.size() == 1
                ? group.get(0).flightNo()
                : group.size() + " uçuş";

          sb.append("  ").append(quote(pair[0]))
            .append(" -> ").append(quote(pair[1]))
            .append(" [label=").append(quote(label)).append("];\n");
       }
    }

    /** Ör: "1 ▸ NA1301\n06:10-09:35 · 2314 km". */
    private static String routeLegLabel(int leg, Flight f) {
       return String.format(Locale.US, "%d ▸ %s\n%s · %,.0f km",
             leg, f.flightNo(), f.timeString(), f.distanceKm());
    }

    /** Grafiğin üst başlığı: rota + kâr + CO2. */
    private static String title(RouteResult r) {
       if (r == null || r.legs().isEmpty()) {
          return "Tarife (rota seçilmedi)";
       }
       return String.format(Locale.US, "%s   |   kâr $%,.0f   |   CO2 %.1f t   |   %d bacak",
             r.pathString(), r.totalProfitUsd(), r.totalCo2Kg() / 1000, r.legs().size());
    }

    /** DOT string'i: tırnak ve satır sonu kaçışlanır. */
    private static String quote(String s) {
       return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
 