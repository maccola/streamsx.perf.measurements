#*******************************************************************************
#  Copyright (C) 2016, International Business Machines Corporation
#  All Rights Reserved
#*******************************************************************************  

DIR=$(dir $(firstword $(MAKEFILE_LIST)))/com.ibm.streamsx.perf.measurements
DOCDIR=$(DIR)/../../doc

BIN_DIR = $(DIR)/impl/java/bin
SRC_DIR = $(DIR)/impl/java/src
JAVA_HOME=$(STREAMS_INSTALL)/java
JAVAC=$(JAVA_HOME)/bin/javac
JAVA_EXTRA=
CLASSPATH = $(STREAMS_INSTALL)/lib/com.ibm.streams.operator.jar:$(STREAMS_INSTALL)/lib/com.ibm.streams.operator.samples.jar:${STREAMS_INSTALL}/lib/com.ibm.streams.management.jmxmp.jar:${STREAMS_INSTALL}/lib/com.ibm.streams.management.mx.jar:$(STREAMS_INSTALL)/system/impl/lib/com.ibm.streams.platform.jar


all: javacompile tk

javacompile:
	rm -rf $(BIN_DIR)
	mkdir -p $(BIN_DIR)
	$(JAVAC) -classpath $(CLASSPATH) $(JAVA_EXTRA) -d $(BIN_DIR) $(SRC_DIR)/com/ibm/streamsx/perf/measurements/MetricsTupleCounter.java


tk:
	spl-make-toolkit -i $(DIR) 

spldoc:
	spl-make-doc -t $(DIR) --output-directory $(DOCDIR)

