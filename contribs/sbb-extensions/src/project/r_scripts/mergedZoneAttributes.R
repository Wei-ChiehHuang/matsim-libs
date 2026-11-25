library(sf)
library(dplyr)
library(readr)

# 1. Read shapefile
shp_data <- st_read("Z:/projects/2022/fabilut/zones/zones_Munich-Bavaria_size_5452_popCap_5000_crs_31468.shp")



# 2. Read CSV file
areaType <- read_csv("C:/models/matsim-libs/contribs/sbb-extensions/src/project/output/zone_areaType_short.csv")
dist2pt <- read_csv("C:/models/matsim-libs/contribs/sbb-extensions/src/project/output/zone_stop_distances_short.csv")

# 3. Join CSV attributes to shapefile
joined_data <- shp_data %>%
               left_join(areaType, by = c("TAZ_ID" = "ZONE")) %>%
               left_join(dist2pt, by = c("TAZ_ID" = "ZONE"))

# 5. Export as a new shapefile
st_write(
  joined_data,
  "C:/modelInputs/abit_muc_2022/input/geo/zoneShapefile/zones_Munich-Bavaria_20261116.shp",
  delete_dsn = TRUE, 
  layer_options = "ENCODING=UTF-8"
)
