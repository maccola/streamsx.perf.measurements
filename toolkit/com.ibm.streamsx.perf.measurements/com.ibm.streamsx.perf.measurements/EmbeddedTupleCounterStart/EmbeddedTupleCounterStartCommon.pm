#*******************************************************************************
#  Copyright (C) 2016, International Business Machines Corporation
#  All Rights Reserved
#*******************************************************************************  

package EmbeddedTupleCounterStartCommon;

use strict;

sub getParms($) {
  my ($model) = (@_);
  
  # Verify parameters
  my $mode = "TupleCount";
  my $modeParm = $model->getParameterByName("measurementMode");
  if (defined($modeParm)) {
    $mode = $modeParm->getValueAt(0)->getCppExpression();      
  }
  
  # tupleCount parameter must be specified if mode is TupleCount
  my $tupleCount;
  if ($mode eq "TupleCount") {
    $tupleCount = $model->getParameterByName("tupleCount");
    my $errMsg = "tupleCount parameter must be set to a number greater than zero when measurementMode parameter is TupleCount.";
    if (!defined($tupleCount)) {
      SPL::CodeGen::exitln($errMsg);        
    }
    else {
      $tupleCount = $tupleCount->getValueAt(0);
      if ($tupleCount->getSPLExpression() < 1) {
        SPL::CodeGen::exitln($errMsg); 
      }
      $tupleCount = $tupleCount->getCppExpression();
    }
  }  

  
  # punctuationCount will default to 1 if not specified.  Really is only
  # going to be used if mode is Punctuation
  my $punctCount = 1;
  my $punctCountParm = $model->getParameterByName("punctCount");
  if (defined($punctCountParm)) {
    $punctCount = $punctCountParm->getValueAt(0)->getCppExpression();      
  }  
  
  # measurementIntervals will default to 1
  my $measurementIntervals = 1;
  my $measurementIntervalsParm = $model->getParameterByName("measurementIntervals");
  if (defined($measurementIntervalsParm)) {
    $measurementIntervals = $measurementIntervalsParm->getValueAt(0)->getCppExpression();      
  }
  
  return($mode, $tupleCount, $punctCount, $measurementIntervals);    
}

1;