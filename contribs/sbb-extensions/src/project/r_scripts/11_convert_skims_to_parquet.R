# 11_convert_skims_to_parquet.R
#
# Converts the skims of CalculateSegmentedSkimsMuc (csv.gz, FROM;TO;VALUE) into one parquet file per mode for the model.
#
# Output (in out_dir, snappy compression, FROM/TO integer, values double, no connection = Inf):
#
#   skim_car.parquet                  FROM, TO, inVehTime_sec, inVehDistance_m
#   skim_bus.parquet                  FROM, TO, accessTime_sec, adaptionTime_sec, egressTime_sec, inVehTime_sec,
#   skim_bus_metro_tram.parquet                 transferTime_sec, totalTravelTime_sec, inVehDistance_m, totalDistance_m
#   skim_bus_metro_tram_rail.parquet
#   skim_walk.parquet                 FROM, TO, walkTime_sec, walkDistance_m
#
# PT columns, computed from the segmented skim:
#   accessTime_sec      = walk_access_time + pt_access_ivt          (walk to the first stop + feeder before the main mode)
#   adaptionTime_sec    = pt_adaptiontime
#   egressTime_sec      = walk_egress_time + pt_egress_ivt          (feeder after the main mode + walk from the last stop)
#   inVehTime_sec       = pt_main_ivt                               (from the first to the last main-mode leg)
#   transferTime_sec    = pt_transfer_walk_time + pt_transfer_wait_time
#   totalTravelTime_sec = access + adaption + egress + inVeh + transfer  (= total_travel_time + adaption time)
#   inVehDistance_m     = pt_main_dist
#   totalDistance_m     = total_distance                            (walk + all in-vehicle + transfer walks)
# Skims: bus = bus_ (main mode bus), bus_metro_tram = btm_ (main tram/U-Bahn, bus feeder),
#        bus_metro_tram_rail = all_ (main rail, bus/tram/U-Bahn feeder).

suppressPackageStartupMessages({
  library(data.table)
  library(arrow)
})

# ------------------------------------------------------------------ settings
r_script_dir <- "C:/models/matsim-libs/contribs/sbb-extensions/src/project/r_scripts"
skim_dir <- "C:/models/matsim-libs/contribs/sbb-extensions/src/project/output/skims_muc"
out_dir <- "C:/modelInputs/abit_muc_2022/input/skims"
pt_skims <- c(bus = "skim_bus", btm = "skim_bus_metro_tram", all = "skim_bus_metro_tram_rail")
# ------------------------------------------------------------------

source(file.path(r_script_dir, "00_skim_utils.R"))
invisible(dir_create(out_dir))
log_step <- function(...) cat(format(Sys.time(), "%H:%M:%S"), ..., "\n")

# reference ids (all matrices of a run have the same order; read_col checks the length, ids are checked once per file set)
log_step("reading ids")
ref <- read_matrix(file.path(skim_dir, "beeline_distances.csv.gz"), ids = TRUE)
n_od <- nrow(ref)
ids <- data.table(FROM = as.integer(ref$from), TO = as.integer(ref$to))
if (anyNA(ids$FROM) || anyNA(ids$TO)) stop("zone ids are not integers")

read_col <- function(file, check_ids = FALSE) {
  f <- file.path(skim_dir, file)
  if (check_ids) {
    m <- read_matrix(f, ids = TRUE)
    if (!identical(m$from, ref$from) || !identical(m$to, ref$to)) stop("zone order of ", file, " differs from beeline_distances.csv.gz")
    return(m$value)
  }
  v <- read_matrix(f)
  if (length(v) != n_od) stop(file, " has ", length(v), " rows, expected ", n_od)
  v
}

write_skim <- function(dt, name) {
  f <- file.path(out_dir, paste0(name, ".parquet"))
  write_parquet(dt, f, compression = "snappy")
  vals <- setdiff(names(dt), c("FROM", "TO"))
  n_inf <- sum(is.infinite(dt[[vals[1]]]))
  log_step(sprintf("written %s: %d rows, %d columns, %d with no connection (Inf), %.0f MB",
                   basename(f), nrow(dt), ncol(dt), n_inf, file.size(f) / 1e6))
}

# ------------------------------------------------------------------ car
log_step("car")
car <- copy(ids)
car[, inVehTime_sec := read_col("car_travel_times.csv.gz", check_ids = TRUE)]
car[, inVehDistance_m := read_col("car_distances.csv.gz")]
stopifnot(!anyNA(car$inVehTime_sec), identical(is.finite(car$inVehTime_sec), is.finite(car$inVehDistance_m)))
write_skim(car, "skim_car")
rm(car); invisible(gc(verbose = FALSE))

# ------------------------------------------------------------------ walk
log_step("walk")
walk <- copy(ids)
walk[, walkTime_sec := read_col("walk_time.csv.gz", check_ids = TRUE)]
walk[, walkDistance_m := read_col("walk_dist.csv.gz")]
stopifnot(!anyNA(walk$walkTime_sec), all(is.finite(walk$walkTime_sec)))
write_skim(walk, "skim_walk")
rm(walk); invisible(gc(verbose = FALSE))

# ------------------------------------------------------------------ PT
for (s in names(pt_skims)) {
  log_step("PT", s, "->", pt_skims[[s]])
  r <- function(ind, check_ids = FALSE) read_col(paste0(s, "_", ind, ".csv.gz"), check_ids)

  pt <- copy(ids)
  pt[, accessTime_sec := r("walk_access_time", check_ids = TRUE) + r("pt_access_ivt")]
  pt[, adaptionTime_sec := r("pt_adaptiontime")]
  pt[, egressTime_sec := r("walk_egress_time") + r("pt_egress_ivt")]
  pt[, inVehTime_sec := r("pt_main_ivt")]
  pt[, transferTime_sec := r("pt_transfer_walk_time") + r("pt_transfer_wait_time")]
  pt[, totalTravelTime_sec := accessTime_sec + adaptionTime_sec + egressTime_sec + inVehTime_sec + transferTime_sec]
  pt[, inVehDistance_m := r("pt_main_dist")]
  pt[, totalDistance_m := r("total_distance")]

  # checks: no NaN; Inf consistently in all columns; total = skim door-to-door time + adaption time
  vals <- setdiff(names(pt), c("FROM", "TO"))
  if (any(vapply(vals, function(v) anyNA(pt[[v]]), logical(1)))) stop(s, ": NaN / NA values")
  inf <- is.infinite(pt$totalTravelTime_sec)
  for (v in vals) if (!identical(is.infinite(pt[[v]]), inf)) stop(s, ": column ", v, " is not Inf for the same OD pairs as totalTravelTime_sec")
  tt <- r("total_travel_time")
  dev <- max(abs((pt$totalTravelTime_sec - pt$adaptionTime_sec - tt)[!inf]), 0)
  if (dev > 5) stop(s, ": total time differs from skim total_travel_time + adaption by up to ", round(dev, 1), " s")
  log_step(sprintf("  check ok: max |total - (skim total + adaption)| = %.2f s", dev))
  rm(tt)

  write_skim(pt, pt_skims[[s]])
  rm(pt); invisible(gc(verbose = FALSE))
}

log_step("done ->", out_dir)
