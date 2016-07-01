#*******************************************************************************
#  Copyright (C) 2016, International Business Machines Corporation
#  All Rights Reserved
#*******************************************************************************  

.PHONY: all clean javacompile

THISDIR=$(dir $(firstword $(MAKEFILE_LIST)))

SPLC = $(STREAMS_INSTALL)/bin/sc
SPLC_FLAGS = -a -t ../../toolkit/com.ibm.streamsx.perf.measurements

SPL_MAIN_COMPOSITE=Main
SPL_DATA_DIR=$(PWD)/data
SPL_CMD_ARGS=

all : distributed

distributed: data
	$(SPLC) $(SPLC_FLAGS) -M $(SPL_MAIN_COMPOSITE) --data-directory $(SPL_DATA_DIR) $(SPL_CMD_ARGS)

standalone: data
	$(SPLC) $(SPLC_FLAGS) -T -M $(SPL_MAIN_COMPOSITE) --data-directory $(SPL_DATA_DIR) $(SPL_CMD_ARGS)

data:
	mkdir -p $(THISDIR)/data

clean:
	$(SPLC) $(SPLC_FLAGS) -C -M $(SPL_MAIN_COMPOSITE)
