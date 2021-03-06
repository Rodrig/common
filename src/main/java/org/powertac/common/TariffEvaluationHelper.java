/*
 * Copyright (c) 2012 by the original author
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.powertac.common;

import org.apache.log4j.Logger;
import org.joda.time.Instant;
import org.powertac.common.spring.SpringApplicationContext;

/**
 * Probe object that can be used by customer models and other players
 * to generate a risk-adjusted estimate of the actual cost of a
 * variable-rate tariff. There are four values for a variable-rate tariff
 * that must be combined to generate an estimate: the broker's claim of
 * the expectedMean price, the brokers commitment to a maxValue of the
 * price, the actual experienced realizedPrice, and the amount of power
 * that has been sold through the tariff. The assumption is that the
 * actual realizedPrice would be more predictive for a tariff
 * with a more substantial price history (large amount of power sold).
 * 
 * Note that some of the data needed to estimate price comes from the
 * Tariff's Rates, which may apply at certain times and/or under certain
 * tier threshold conditions, and some comes from the Tariff itself.
 * 
 * The price estimate is generated as <br/>
 * &nbsp;&nbsp;
 * alpha * (wtExpected * expectedMean + wtMax * maxValue)<br/>
 * &nbsp;&nbsp;&nbsp;&nbsp;
 * + (1 - alpha) * realizedPrice<br/>
 * where alpha = 1 - wtRealized * (1 - 1 / (1 + totalKWh / soldThreshold)).
 * 
 * In the case where multiple variable Rates apply, the values of 
 * expectedMean and maxValue are the usage-weighted means of the
 * corresponding values from the individual Rates.
 * 
 * Usage: A Customer may need to evaluate multiple tariff offerings by
 * estimating cost over a week or more of 1-hour timeslots. Tier usage
 * is computed over a 24-hour period. Therefore, the recommended usage
 * is to create a single instance of this class for each customer model,
 * and re-initialize it for each tariff. Therefore, only a default
 * constructor is provided, along with an init() method that clears out
 * state and optionally sets parameter values.
 * 
 * @author John Collins
 */
public class TariffEvaluationHelper
{
  static private Logger log = Logger.getLogger(TariffEvaluationHelper.class.getName());

  // weights
  private double wtExpected = 0.6;
  private double wtMax = 0.4;
  private double wtRealized = 0.8;
  private double soldThreshold = 10000.0;
  
  // normalized weights
  private double normWtExpected = 0.0;
  private double normWtMax = 0.0;

  // evaluation state
  private double alpha = 0.0;
  private Tariff tariff = null;
  
  // need time service, but this is not a Spring managed bean
  private TimeService timeService;
  
  /**
   * Default constructor
   */
  public TariffEvaluationHelper ()
  {
    super();
  }
  
  /**
   * Initialize, setting parameters, then normalize the weights for
   * expectedMean and maxValue.
   */
  public void init (double wtExpected, double wtMax,
                    double wtRealized, double soldThreshold)
  {
    setWtExpected(wtExpected);
    setWtMax(wtMax);
    setWtRealized(wtRealized);
    this.soldThreshold = soldThreshold;
    init();
  }
  
  /**
   * Initialize, without changing parameter settings
   */
  public void init ()
  {
    alpha = 0.0;
    tariff = null;
    if (null == timeService) {
      timeService = (TimeService) SpringApplicationContext.getBean("timeService");
    }
  }
  
  /**
   * Estimate the total cost of buying the given amounts of power
   * from the given tariff, starting in the following timeslot.
   * Payments include usage charges, and periodic payments just in case
   * <code>includePeriodicCharge</code> is true. They do not
   * include signup or withdrawal charges.
   * Note that there is a strong assumption that the projected usage
   * is for a single customer, not the total population in some model.
   * This assumption is embedded in the structure of usage tiers in the
   * tariff.
   */
  public double estimateCost (Tariff tariff, double[] usage,
                              boolean includePeriodicCharge)
  {
    init();
    this.tariff = tariff;
    computeAlpha(tariff);
    double dailyUsage = 0.0;
    double result = 0.0;
    Instant time = timeService.getCurrentTime();
    for (int index = 0; index < usage.length; index++) {
      time = time.plus(TimeService.HOUR);
      result += tariff.getUsageCharge(time, usage[index], dailyUsage, this);
      if (includePeriodicCharge)
        result += tariff.getPeriodicPayment() / 24.0;
      if (timeService.getHourOfDay() == 0) {
        //reset the daily usage counter
        dailyUsage = 0.0;
      }
      else {
        dailyUsage += usage[index];
      }
    }
    return result;
  }

  private void computeAlpha (Tariff tariff)
  {
    alpha = 1.0 - getWtRealized()
            * (1.0 - 1.0 / (1.0 + tariff.getTotalUsage()
                                  / getSoldThreshold()));
  }
  
  /**
   * Returns aggregate estimated cost, including periodic charges
   */
  public double estimateCost (Tariff tariff, double[] usage)
  {
    return estimateCost(tariff, usage, true);
  }
  
  
  /**
   * Returns the cost estimate in the form of an array of the same shape
   * as the usage vector. Each element of the result corresponds to the
   * corresponding element of the usage array. Periodic charges are included
   * just in case <code>includePeriodicCharge</code> is true.
   */
  public double[] estimateCostArray (Tariff tariff, double[] usage,
                                     boolean includePeriodicCharge)
  {
    init();
    this.tariff = tariff;
    computeAlpha(tariff);
    double dailyUsage = 0.0;
    double[] result = new double[usage.length];
    Instant time = timeService.getCurrentTime();
    for (int index = 0; index < usage.length; index++) {
      time = time.plus(TimeService.HOUR);
      result[index] = tariff.getUsageCharge(time, usage[index], dailyUsage, this);
      if (includePeriodicCharge)
        result[index] += tariff.getPeriodicPayment() / 24.0;
      if (timeService.getHourOfDay() == 0) {
        //reset the daily usage counter
        dailyUsage = 0.0;
      }
      else {
        dailyUsage += usage[index];
      }
    }
    return result;    
  }
  
  /**
   * Returns a cost estimate in array form, including periodic charges.
   */
  public double[] estimateCostArray (Tariff tariff, double[] usage)
  {
    return estimateCostArray(tariff, usage, true);
  }

  /**
   * Combines the expectedMean and maxValue of a Rate as specified by
   * parameters.
   */
  double getWeightedValue (Rate rate)
  {
    return (alpha * (getNormWtExpected() * rate.getExpectedMean()
                     + getNormWtMax() * rate.getMaxValue())
            + (1.0 - alpha) * tariff.getRealizedPrice());
  }
  
  /**
   * Parameter access
   */
  // expected
  public double getWtExpected ()
  {
    return wtExpected;
  }
  
  public double getNormWtExpected ()
  {
    return normWtExpected;
  }
  
  public void setWtExpected (double wt)
  {
    wtExpected = wt;
    normalizeWeights();
  }
  
  // max
  public double getWtMax ()
  {
    return wtMax;
  }
  
  public double getNormWtMax ()
  {
    return normWtMax;
  }
  
  public void setWtMax (double wt)
  {
    wtMax = wt;
    normalizeWeights();
  }
  
  // wr
  public double getWtRealized ()
  {
    return wtRealized;
  }
  
  public void setWtRealized (double wt)
  {
    if (wt < 0.0 || wt > 1.0) {
      log.error("realizedPrice weight " + wt + " out of range");
      wt = Math.min(Math.max(wt, 0.0), 1.0);
    }
    wtRealized = wt;
  }
  
  // st
  public double getSoldThreshold ()
  {
    return soldThreshold;
  }
  
  public void setSoldThreshold (double st)
  {
    soldThreshold = st;
  }
  
  // normalizes the weights for expected and max so they add to 1
  private void normalizeWeights ()
  {
    double sum = wtExpected + wtMax;
    normWtExpected = wtExpected / sum;
    normWtMax = wtMax / sum;
  }
}
