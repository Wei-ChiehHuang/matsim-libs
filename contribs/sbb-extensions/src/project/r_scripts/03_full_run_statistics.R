# 03_full_run_statistics.R
#
# Statistical plausibility checks of the segmented PT skims for the full run (5452 zones = 29.7 M OD pairs).
# Run 01_validate_skims.R first (hard checks). This script looks at distributions:
#
#   1. share of unreachable (Infinity) OD pairs per skim, intrazonal / interzonal
#   2. summary per distance band: travel time, door-to-door speed, transfers, main share, availability, frequency,
#      share of pairs where walking is faster
#   3. plots: PT time vs beeline distance, main share and availability histograms, main-mode in-vehicle speed,
#      door-to-door speed by distance band, frequency by distance band, cross-skim scatter
#   4. cross-skim: pairs where a higher-level skim is > 10 % slower than the lower-level skim
#   5. per origin zone: share of reachable destinations and mean availability (+ maps if a shapefile is given)
#   6. car (if the car skim exists): car time, network speed and detour by distance band; PT / car time ratio by
#      distance band and skim; plots of car speed, PT/car ratio and PT vs car scatter
#
# Output: <skim_dir>/statistics/
# Memory: one skim at a time, about 8 columns x 29.7 M rows (~2-3 GB). Plots use a random sample of OD pairs.

suppressPackageStartupMessages({
  library(data.table)
  library(ggplot2)
})

# ------------------------------------------------------------------ settings
r_script_dir <- "C:/models/matsim-libs/contribs/sbb-extensions/src/project/r_scripts"
skim_dir <- "C:/models/matsim-libs/contribs/sbb-extensions/src/project/output/skims_muc_test"
zones_shapefile <- "Z:/projects/2022/fabilut/zones/zones_Munich-Bavaria_size_5452_popCap_5000_crs_31468.shp"  # NULL = no maps
zones_id_attribute <- "TAZ_ID"
map_origin_zone <- NULL            # e.g. "2641": map of travel times from this zone per skim; NULL = no such map
plot_sample_size <- 200000         # OD pairs per skim used in scatter plots
slower_threshold_rel <- 0.10       # cross-skim: higher-level skim slower by > 10 %
slower_threshold_abs <- 60         # ... and by > 60 s
top_n_list <- 500                  # rows in the cross-skim list
seed <- 4711
# ------------------------------------------------------------------

source(file.path(r_script_dir, "00_skim_utils.R"))
options(width = 220)
out_dir <- dir_create(file.path(skim_dir, "statistics"))
set.seed(seed)

base <- add_distance_band(load_base(skim_dir))
n_od <- nrow(base)
cat(sprintf("zones: %d, OD pairs: %d\n", length(unique(base$from)), n_od))
sample_idx <- sort(sample.int(n_od, min(n_od, plot_sample_size)))

cols <- c("total_travel_time", "pt_main_ivt", "pt_main_dist", "main_share_time", "main_mode_availability",
          "pt_transfer_count", "pt_frequency", "pt_ivt_total")

inf_share <- list()
band_summary <- list()
samples <- list()
per_origin <- list()
totals <- list()
map_values <- list()

q <- function(x, p) as.numeric(quantile(x, p, na.rm = TRUE, names = FALSE))

for (s in SKIMS) {
  cat("skim", s, "...\n")
  d <- load_skim(skim_dir, s, base, indicators = cols)
  d[, reach := is.finite(total_travel_time)]
  d[, tt_min := total_travel_time / 60]
  d[, d2d_speed := ifelse(reach & beeline > 0, beeline / total_travel_time * 3.6, NA_real_)]            # km/h, door-to-door
  d[, main_speed := ifelse(reach & pt_main_ivt > 60, pt_main_dist / pt_main_ivt * 3.6, NA_real_)]      # km/h, in vehicle
  d[, walk_faster := reach & walk_time < total_travel_time]

  # 1. unreachable share
  inf_share[[s]] <- d[, .(skim = s, n = .N, unreachable = sum(!reach), share_unreachable = round(mean(!reach), 4)),
                      by = .(type = ifelse(intrazonal, "intrazonal", "interzonal"))]

  # 2. distance bands
  band_summary[[s]] <- d[, .(
    skim = s, n = .N,
    share_reachable = round(mean(reach), 3),
    tt_p10_min = round(q(tt_min[reach], 0.1), 1),
    tt_median_min = round(q(tt_min[reach], 0.5), 1),
    tt_p90_min = round(q(tt_min[reach], 0.9), 1),
    d2d_speed_median_kmh = round(q(d2d_speed, 0.5), 1),
    main_speed_median_kmh = round(q(main_speed, 0.5), 1),
    transfers_median = round(q(pt_transfer_count[reach], 0.5), 2),
    main_share_median = round(q(main_share_time[reach], 0.5), 2),
    availability_median = round(q(main_mode_availability[reach], 0.5), 2),
    share_availability_lt_0_2 = round(mean(main_mode_availability[reach] < 0.2), 3),
    frequency_median_per_h = round(q(pt_frequency[reach], 0.5), 1),
    share_walk_faster = round(mean(walk_faster[reach]), 3)
  ), by = dist_band][order(dist_band)]

  # 3. sample for plots
  samples[[s]] <- d[sample_idx, .(skim = s, from, to, beeline, dist_band, intrazonal, reach, tt_min, walk_min = walk_time / 60,
                                  main_share_time, main_mode_availability, main_speed, d2d_speed, pt_frequency)]

  # 5. per origin zone (interzonal)
  per_origin[[s]] <- d[intrazonal == FALSE, .(skim = s, share_reachable = mean(reach),
                                              mean_availability = mean(main_mode_availability),
                                              median_tt_min_reachable = q(tt_min[reach], 0.5)), by = from]

  if (!is.null(map_origin_zone)) map_values[[s]] <- d[from == map_origin_zone, .(skim = s, to, tt_min, main_mode_availability)]
  totals[[s]] <- d$total_travel_time
  rm(d); gc(verbose = FALSE)
}

# ------------------------------------------------------------------ tables
inf_tab <- rbindlist(inf_share)
band_tab <- rbindlist(band_summary)
fwrite(inf_tab, file.path(out_dir, "unreachable_share.csv"), sep = ";")
fwrite(band_tab, file.path(out_dir, "distance_band_summary.csv"), sep = ";")
cat("\n==== unreachable (Infinity) share\n"); print(inf_tab)
cat("\n==== summary by distance band\n"); print(band_tab, nrows = 200)

# ------------------------------------------------------------------ 4. cross-skim
cross_list <- list()
cross_tab <- list()
for (pair in list(c("btm", "bus"), c("all", "btm"), c("all", "bus"))) {
  hi <- pair[1]; lo <- pair[2]
  a <- totals[[hi]]; b <- totals[[lo]]
  both <- is.finite(a) & is.finite(b)
  slower <- both & a > b * (1 + slower_threshold_rel) & a > b + slower_threshold_abs
  cross_tab[[paste(hi, lo)]] <- data.table(comparison = paste0(hi, " vs ", lo), dist_band = base$dist_band, both, slower)[
    , .(n_both_reachable = sum(both), n_slower = sum(slower), share_slower = round(sum(slower) / max(1, sum(both)), 4)), by = .(comparison, dist_band)][order(dist_band)]
  idx <- which(slower)
  if (length(idx) > 0) {
    lst <- data.table(comparison = paste0(hi, " vs ", lo), from = base$from[idx], to = base$to[idx], beeline_km = round(base$beeline[idx] / 1000, 2),
                      tt_hi_min = round(a[idx] / 60, 1), tt_lo_min = round(b[idx] / 60, 1), diff_min = round((a[idx] - b[idx]) / 60, 1))
    cross_list[[paste(hi, lo)]] <- head(lst[order(-diff_min)], top_n_list)
  }
}
cross_tab <- rbindlist(cross_tab)
fwrite(cross_tab, file.path(out_dir, "cross_skim_slower_by_band.csv"), sep = ";")
if (length(cross_list) > 0) fwrite(rbindlist(cross_list), file.path(out_dir, "cross_skim_slower_pairs.csv"), sep = ";")
cat("\n==== higher-level skim slower than lower-level skim (expected: few; option (i) averages only main-mode connections)\n")
print(cross_tab, nrows = 200)

# ------------------------------------------------------------------ 3. plots
smp <- rbindlist(samples)
smp[, skim_label := factor(SKIM_LABELS[skim], levels = SKIM_LABELS)]
r <- smp[reach == TRUE & intrazonal == FALSE]

# walk reference line: walk time = beeline * factor / speed (taken from the data)
walk_ref <- smp[skim == "bus" & beeline > 0, .(k = median(walk_min / (beeline / 1000)))]$k

# trend: median per 2-km distance bin (robust, also for few points)
trend <- r[, .(tt_median = median(tt_min), n = .N), by = .(skim_label, km = (floor(beeline / 2000) + 0.5) * 2)][n >= 3][order(skim_label, km)]
y_max <- q(r$tt_min, 0.995) * 1.05
p <- ggplot(r, aes(beeline / 1000, tt_min)) +
  geom_point(alpha = 0.3, size = 0.8) +
  geom_line(data = trend, aes(km, tt_median), colour = "red", linewidth = 0.8) +
  geom_abline(slope = walk_ref, intercept = 0, colour = "blue", linetype = "dashed") +
  facet_wrap(~skim_label) +
  coord_cartesian(ylim = c(0, y_max)) +
  labs(x = "beeline distance [km]", y = "PT door-to-door time [min]",
       title = "PT travel time vs distance (interzonal, reachable)", subtitle = "red: median per 2-km bin (bins with >= 3 pairs), blue dashed: walking") +
  theme_bw()
ggsave(file.path(out_dir, "tt_vs_beeline.png"), p, width = 12, height = 5, dpi = 150)

p <- ggplot(r[skim != "bus"], aes(main_share_time)) +
  geom_histogram(bins = 40, boundary = 0) + facet_wrap(~skim_label, scales = "free_y") +
  labs(x = "main share (in-vehicle time, by segment)", y = "OD pairs (sample)", title = "Main-mode share") + theme_bw()
ggsave(file.path(out_dir, "main_share_hist.png"), p, width = 10, height = 4.5, dpi = 150)

p <- ggplot(r, aes(main_mode_availability)) +
  geom_histogram(bins = 40, boundary = 0) + facet_wrap(~skim_label, scales = "free_y") +
  labs(x = "main-mode availability", y = "OD pairs (sample)", title = "Share of the hour / point pairs served by the main mode") + theme_bw()
ggsave(file.path(out_dir, "availability_hist.png"), p, width = 12, height = 4.5, dpi = 150)

p <- ggplot(r[!is.na(main_speed)], aes(main_speed)) +
  geom_histogram(bins = 60) + facet_wrap(~skim_label, scales = "free") +
  labs(x = "main-mode in-vehicle speed [km/h]", y = "OD pairs (sample)", title = "Main-mode in-vehicle speed") + theme_bw()
ggsave(file.path(out_dir, "main_speed_hist.png"), p, width = 12, height = 4.5, dpi = 150)

p <- ggplot(r[!is.na(d2d_speed)], aes(dist_band, d2d_speed, fill = skim_label)) +
  geom_boxplot(outlier.size = 0.3) +
  labs(x = "beeline distance band", y = "door-to-door speed [km/h] (beeline / PT time)", fill = NULL,
       title = "Door-to-door PT speed by distance") + theme_bw() + theme(legend.position = "bottom")
ggsave(file.path(out_dir, "d2d_speed_by_band.png"), p, width = 11, height = 5.5, dpi = 150)

p <- ggplot(r, aes(dist_band, pt_frequency, fill = skim_label)) +
  geom_boxplot(outlier.size = 0.3) + scale_y_continuous(trans = "log1p") +
  labs(x = "beeline distance band", y = "frequency [per hour] (log scale)", fill = NULL, title = "Perceived frequency by distance") +
  theme_bw() + theme(legend.position = "bottom")
ggsave(file.path(out_dir, "frequency_by_band.png"), p, width = 11, height = 5.5, dpi = 150)

wide <- dcast(smp[intrazonal == FALSE, .(from, to, skim, tt_min)], from + to ~ skim, value.var = "tt_min")
cs <- rbind(wide[is.finite(btm) & is.finite(bus), .(comparison = "bus + tram/metro vs bus", lo = bus, hi = btm)],
            wide[is.finite(all) & is.finite(btm), .(comparison = "all vs bus + tram/metro", lo = btm, hi = all)])
if (nrow(cs) > 0) {
  p <- ggplot(cs, aes(lo, hi)) +
    geom_point(alpha = 0.2, size = 0.6) + geom_abline(slope = 1, intercept = 0, colour = "red") +
    facet_wrap(~comparison, scales = "free") +
    labs(x = "lower-level skim [min]", y = "higher-level skim [min]", title = "Cross-skim travel time (points above the red line: higher level slower)") +
    theme_bw()
  ggsave(file.path(out_dir, "cross_skim_scatter.png"), p, width = 11, height = 5, dpi = 150)
}

# ------------------------------------------------------------------ 6. car skim: speed by distance band, car vs PT
car <- base[, .(from, to, beeline, intrazonal, dist_band, walk_time)]
if (load_car(skim_dir, car)) {
  car[, creach := is.finite(car_tt)]
  car[, net_speed := ifelse(creach & car_tt > 0, car_dist / car_tt * 3.6, NA_real_)]           # km/h along the route
  car[, d2d_speed := ifelse(creach & car_tt > 0 & beeline > 0, beeline / car_tt * 3.6, NA_real_)] # km/h beeline
  car[, detour := ifelse(creach & beeline > 0, car_dist / beeline, NA_real_)]

  car_band <- car[, .(
    n = .N,
    share_reachable = round(mean(creach), 3),
    car_tt_p10_min = round(q(car_tt[creach] / 60, 0.1), 1),
    car_tt_median_min = round(q(car_tt[creach] / 60, 0.5), 1),
    car_tt_p90_min = round(q(car_tt[creach] / 60, 0.9), 1),
    net_speed_p10_kmh = round(q(net_speed, 0.1), 1),
    net_speed_median_kmh = round(q(net_speed, 0.5), 1),
    net_speed_p90_kmh = round(q(net_speed, 0.9), 1),
    d2d_speed_median_kmh = round(q(d2d_speed, 0.5), 1),
    detour_median = round(q(detour, 0.5), 2)
  ), by = dist_band][order(dist_band)]
  fwrite(car_band, file.path(out_dir, "car_distance_band_summary.csv"), sep = ";")
  cat("\n==== car by distance band\n"); print(car_band, nrows = 200)

  # PT vs car (both reachable)
  pt_car <- rbindlist(lapply(SKIMS, function(s) {
    pt <- totals[[s]]
    ok <- car$creach & is.finite(pt) & car$car_tt > 0
    data.table(skim = s, dist_band = car$dist_band, ok, ratio = ifelse(ok, pt / car$car_tt, NA_real_),
               pt_faster = ok & pt < car$car_tt)[
      , .(n_both_reachable = sum(ok),
          ratio_p10 = round(q(ratio, 0.1), 2), ratio_median = round(q(ratio, 0.5), 2), ratio_p90 = round(q(ratio, 0.9), 2),
          share_pt_faster = round(sum(pt_faster) / max(1, sum(ok)), 4)), by = .(skim, dist_band)][order(skim, dist_band)]
  }))
  fwrite(pt_car, file.path(out_dir, "pt_vs_car_by_band.csv"), sep = ";")
  cat("\n==== PT / car travel time ratio by distance band (expected: mostly > 1; < 1 mainly for rail on long trips)\n")
  print(pt_car, nrows = 200)

  # plots (same OD sample as above)
  cs_car <- car[sample_idx][creach == TRUE & intrazonal == FALSE]
  p <- ggplot(cs_car[!is.na(net_speed)], aes(dist_band, net_speed)) +
    geom_boxplot(outlier.size = 0.3, fill = "grey80") +
    labs(x = "beeline distance band", y = "car network speed [km/h] (car distance / car time)",
         title = "Car speed by distance band") + theme_bw()
  ggsave(file.path(out_dir, "car_speed_by_band.png"), p, width = 9, height = 5, dpi = 150)

  cmp <- merge(smp[reach == TRUE & intrazonal == FALSE, .(from, to, skim_label, dist_band, tt_min)],
               cs_car[, .(from, to, car_min = car_tt / 60)], by = c("from", "to"))
  if (nrow(cmp) > 0) {
    p <- ggplot(cmp, aes(dist_band, tt_min / car_min, fill = skim_label)) +
      geom_hline(yintercept = 1, colour = "red") +
      geom_boxplot(outlier.size = 0.3) + scale_y_log10() +
      labs(x = "beeline distance band", y = "PT time / car time (log scale)", fill = NULL,
           title = "PT vs car travel time", subtitle = "below the red line: PT faster than car") +
      theme_bw() + theme(legend.position = "bottom")
    ggsave(file.path(out_dir, "pt_car_ratio_by_band.png"), p, width = 11, height = 5.5, dpi = 150)

    lim <- c(0, q(c(cmp$tt_min, cmp$car_min), 0.995) * 1.05)
    p <- ggplot(cmp, aes(car_min, tt_min)) +
      geom_point(alpha = 0.3, size = 0.8) + geom_abline(slope = 1, intercept = 0, colour = "red") +
      facet_wrap(~skim_label) + coord_cartesian(xlim = lim, ylim = lim) +
      labs(x = "car time [min]", y = "PT time [min]", title = "PT vs car travel time (above the red line: car faster)") + theme_bw()
    ggsave(file.path(out_dir, "pt_vs_car_scatter.png"), p, width = 12, height = 4.8, dpi = 150)
  }
}

# ------------------------------------------------------------------ 5. per origin zone + maps
po <- rbindlist(per_origin)
fwrite(po, file.path(out_dir, "per_origin_zone.csv"), sep = ";")
cat("\n==== origin zones with no reachable destination (zone without usable stop?)\n")
print(po[share_reachable == 0, .N, by = skim])

if (!is.null(zones_shapefile) && file.exists(zones_shapefile)) {
  suppressPackageStartupMessages(library(sf))
  zones <- st_read(zones_shapefile, quiet = TRUE)
  zones$zone_id <- as.character(zones[[zones_id_attribute]])
  zones <- zones[zones$zone_id %in% unique(base$from), ]
  if (nrow(zones) > 0) {
    pz <- merge(zones, po, by.x = "zone_id", by.y = "from")
    pz$skim_label <- factor(SKIM_LABELS[pz$skim], levels = SKIM_LABELS)
    p <- ggplot(pz) + geom_sf(aes(fill = share_reachable), colour = NA) + facet_wrap(~skim_label) +
      scale_fill_viridis_c(limits = c(0, 1)) + labs(fill = "share", title = "Share of destinations reachable with the main mode") + theme_bw()
    ggsave(file.path(out_dir, "map_share_reachable.png"), p, width = 14, height = 6, dpi = 150)
    p <- ggplot(pz) + geom_sf(aes(fill = mean_availability), colour = NA) + facet_wrap(~skim_label) +
      scale_fill_viridis_c(limits = c(0, 1)) + labs(fill = "availability", title = "Mean main-mode availability per origin zone") + theme_bw()
    ggsave(file.path(out_dir, "map_availability.png"), p, width = 14, height = 6, dpi = 150)

    if (!is.null(map_origin_zone)) {
      mv <- rbindlist(map_values)
      mv[!is.finite(tt_min), tt_min := NA]
      mz <- merge(zones, mv, by.x = "zone_id", by.y = "to")
      mz$skim_label <- factor(SKIM_LABELS[mz$skim], levels = SKIM_LABELS)
      p <- ggplot(mz) + geom_sf(aes(fill = tt_min), colour = NA) +
        geom_sf(data = zones[zones$zone_id == map_origin_zone, ], fill = NA, colour = "red", linewidth = 0.8) +
        facet_wrap(~skim_label) + scale_fill_viridis_c(option = "magma", direction = -1, na.value = "grey85") +
        labs(fill = "minutes", title = paste0("PT travel time from zone ", map_origin_zone), subtitle = "grey: no main-mode connection") + theme_bw()
      ggsave(file.path(out_dir, paste0("map_tt_from_", map_origin_zone, ".png")), p, width = 14, height = 6, dpi = 150)
    }
  } else {
    cat("no zones of the skim found in the shapefile (attribute ", zones_id_attribute, ")\n")
  }
} else {
  cat("no shapefile, maps skipped\n")
}

cat("\nall outputs in", out_dir, "\n")
