/*
  Copyright 2006 by Sean Luke
  Licensed under the Academic Free License version 3.0
  See the file "LICENSE" for more information
*/


package ec.es;
import java.util.ArrayList;
import java.util.Arrays;

import ec.*;
import ec.util.*;

/* 
 * MuCommaLambdaBreeder.java
 * 
 * Created: Thu Sep  7 17:27:47 2000
 * By: Sean Luke
 */

/**
 * MuCommaLambdaBreeder is a Breeder which, together with
 * ESSelection, implements the (mu,lambda) breeding strategy and gathers
 * the comparison data you can use to implement a 1/5-rule mutation mechanism.
 * 
 * <p>Evolution strategies breeders require a "mu" parameter and a "lambda"
 * parameter for each subpopulation.  "mu" refers to the number of parents
 * from which the new population will be built.  "lambda" refers to the
 * number of children generated by the mu parents.  Subpopulation sizes
 * will change as necessary to accommodate this fact in later generations.
 * The only rule for initial subpopulation sizes is that they must be
 * greater than or equal to the mu parameter for that subpopulation.
 *
 * <p>You can now set your initial subpopulation
 * size to whatever you like, totally independent of lambda and mu,
 * as long as it is &gt;= mu.
 *
 * <p>MuCommaLambdaBreeder stores mu and lambda values for each subpopulation
 * in the population, as well as comparisons.  A comparison tells you
 * if &gt;1/5, &lt;1/5 or =1/5 of the new population was better than its
 * parents (the so-called evolution strategies "one-fifth rule".
 * Although the comparisons are gathered, no mutation objects are provided
 * which actually <i>use</i> them -- you're free to use them in any mutation
 * objects you care to devise which requires them.
 *
 * <p>To do evolution strategies evolution, the
 * breeding pipelines should contain at least one ESSelection selection method.
 * While a child is being generated by the pipeline, the ESSelection object will return a parent
 * from the pool of mu parents.  The particular parent is chosen round-robin, so all the parents
 * will have an equal number of children.  It's perfectly fine to have more than one ESSelection
 * object in the tree, or to call the same one repeatedly during the course of generating a child;
 * all such objects will consistently return the same parent.  They only increment to the next
 * parent in the pool of mu parents after the child has been created from the pipeline.  You can
 * also mix ESSelection operators with other operators (like Tournament Selection).  But you ought
 * to have <b>at least one</b> ESSelection operator in the pipeline -- else it wouldn't be Evolution
 * Strategies, would it?
 
 <p><b>Parameters</b><br>
 <table>
 <tr><td valign=top>es.lambda.<i>subpop-num</i><br>
 <font size=-1>int >= 0</font></td><td>Specifies the 'lambda' parameter for the subpopulation.</td>
 </tr>
 <tr><td valign=top>es.mu.<i>subpop-num</i><br>
 <font size=-1>int:  a multiple of "lambda"</font></td><td>Specifies the 'mu' parameter for the subpopulation.</td>
 </tr>
 </table>

 * @author Sean Luke
 * @version 1.0 
 */

public class MuCommaLambdaBreeder extends Breeder
{    
    public static final String P_MU = "mu";
    public static final String P_MU_FRACTION = "mu-fraction";
    public static final String P_LAMBDA = "lambda";

    public int[] mu;
    public int[] lambda;
    
    public Population parentPopulation;

    public byte[] comparison; 
    public static final byte C_OVER_ONE_FIFTH_BETTER = 1;
    public static final byte C_UNDER_ONE_FIFTH_BETTER = -1;
    public static final byte C_EXACTLY_ONE_FIFTH_BETTER = 0;
   
    // This is a DOUBLE ARRAY of ARRAYLISTS of <INDIVIDUALS>
    // Individuals are stored here by the breed pop chunk methods, and afterwards
    // we coalesce them into the new population. 
    public ArrayList newIndividuals[/*subpop*/][/*thread*/];
        
    /** Modified by multiple threads, don't fool with this */
    public int[] count;

    /** lambda should be no SMALLER than mu times this value. 
        This varies between (mu,lambda) (where it's 2) and
        (mu + lambda) (where it's 1).
    */
    public int maximumMuLambdaDivisor() { return 2; }

    public void setup(final EvolutionState state, final Parameter base)
    {
        // we're not using the base
        Parameter p = new Parameter(Initializer.P_POP).push(Population.P_SIZE);
        int size = state.parameters.getInt(p,null,1);  // if size is wrong, we'll let Population complain about it -- for us, we'll just make 0-sized arrays and drop out.
        
        mu = new int[size];
        lambda = new int[size];
        comparison = new byte[size];
        
        // load mu and lambda data
        for(int x=0;x<size;x++)
            {
                Parameter pp = new Parameter(Initializer.P_POP).push(Population.P_SUBPOP).push(""+x).push(Subpopulation.P_SUBPOPSIZE);
                int ppval = state.parameters.getInt(pp, null, 1);
                if (state.parameters.exists(ESDefaults.base().push(P_LAMBDA).push(""+x),null))  // we have a lambda
                    {
                        lambda[x] = state.parameters.getInt(ESDefaults.base().push(P_LAMBDA).push(""+x),null,1);            
                        if (lambda[x]==0) state.output.error("lambda must be an integer >= 1",ESDefaults.base().push(P_LAMBDA).push(""+x));
                    }
                else
                    {
                        state.output.warning("lambda not specified for subpopulation " + x + ", setting it to the subopulation size, that is, " + ppval + ".", 
                                             ESDefaults.base().push(P_LAMBDA).push(""+x),null);
                        lambda[x] = ppval;
                        if (lambda[x] == 0)
                            state.output.error("Subpouplation Size must be >= 1", pp, null);
                    }
                                
                if (state.parameters.exists(ESDefaults.base().push(P_MU).push(""+x),null))  // we defined mu
                    {
                        // did we also define a mu-fraction?
                        if (state.parameters.exists(ESDefaults.base().push(P_MU_FRACTION).push(""+x), null))
                            state.output.warning("Defined both a mu and mu-fraction for subpopulation " + x + ".  Only mu will be used. ", 
                                                 ESDefaults.base().push(P_MU).push(""+x),
                                                 ESDefaults.base().push(P_MU_FRACTION).push(""+x));
                
                        mu[x] = state.parameters.getInt(ESDefaults.base().push(P_MU).push(""+x),null,1);       
                        if (mu[x]==0) state.output.error("mu must be an integer >= 1",ESDefaults.base().push(P_MU).push(""+x), null);
                        else if (lambda[x] % mu[x] != 0)
                            {
                                if (mu[x] > lambda[x] / maximumMuLambdaDivisor())
                                    {
                                        state.output.warning("mu (" + mu[x] + ") for subpopulation " + x + " is greater than lambda (" + lambda[x] + ") / " + maximumMuLambdaDivisor() + ".  Mu will be set to half of lambda, that is, " + lambda[x] / maximumMuLambdaDivisor() + ".");                        
                                        mu[x] = lambda[x] / maximumMuLambdaDivisor();
                                    }
        
                                if (lambda[x] % mu[x] != 0)  // check again
                                    state.output.error("mu must be a divisor of lambda", ESDefaults.base().push(P_MU).push(""+x));
                            }
                        else if (mu[x] > ppval)
                            {
                                state.output.warning("mu is presently > the initial subpopulation size.  Mu will be set to the subpopulation size, that is, " + ppval + ".", ESDefaults.base().push(P_MU).push(""+x), null);
                                mu[x] = ppval;
                            }
                    }
                else if (state.parameters.exists(ESDefaults.base().push(P_MU_FRACTION).push(""+x), null))  // we defined mu in terms of a fraction
                    {
                        double mufrac = state.parameters.getDoubleWithMax(ESDefaults.base().push(P_MU_FRACTION).push(""+x), null, 0.0, 1.0 / maximumMuLambdaDivisor());
                        if (mufrac < 0.0)
                            state.output.fatal("Mu-Fraction must be a value between 0.0 and " + 1.0 / maximumMuLambdaDivisor(), ESDefaults.base().push(P_MU_FRACTION).push(""+x), null);
                                
                        int m = (int)Math.max(lambda[x] * mufrac, 1.0);
                        mu[x] = m;
                        // find the largest divisor of lambda[x] which is <= m. This is ugly
                        double val = lambda[x] / (double) mu[x];
                        while (val != (int) val)
                            {
                                mu[x]--;
                                val = lambda[x] / (double) mu[x];
                            }
                        state.output.message("Mu-Fraction " + mufrac + " yields a mu of " + m + ", adjusted to " + mu[x]);
                    }
                else state.output.fatal("Neither a Mu or a Mu-Fraction was provided for subpopulation " + x, ESDefaults.base().push(P_MU).push(""+x), ESDefaults.base().push(P_MU_FRACTION).push(""+x));
            }
        state.output.exitIfErrors();
    }



    /** Sets all subpopulations in pop to the expected lambda size.  Does not fill new slots with individuals. */
    //    public Population setToLambda(Population pop, EvolutionState state)
    //        {
    //        for(int x = 0; x< pop.subpops.size(); x++)
    //            {
    //            int s = lambda[x];
    //            
    //            System.out.println("size of s is "+s);
    //            System.out.println("size of size is "+pop.subpops.get(x).individuals.size());
    //            
    //            // check to see if the array's not the right size
    //            if (pop.subpops.get(x).individuals.size() != s)
    //                // need to increase
    //                {
    //                Individual[] newinds = new Individual[s];
    //                System.arraycopy(pop.subpops.get(x).individuals,0,newinds,0,
    //                    s < pop.subpops.get(x).individuals.size() ?
    //                    s : pop.subpops.get(x).individuals.size());
    //                pop.subpops.get(x).individuals = new ArrayList<Individual>(Arrays.asList(newinds));
    //                }
    //            }
    //        return pop;
    //        }
                

    public Population breedPopulation(EvolutionState state) 
    {
        // Complete 1/5 statistics for last population
        
        if (parentPopulation != null)
            {
                // Only go from 0 to lambda-1, as the remaining individuals may be parents.
                // A child C's parent's index I is equal to C / mu[subpopulation].
                for (int x = 0; x< state.population.subpops.size(); x++)
                    {
                        int numChildrenBetter = 0;
                        for (int i = 0; i < lambda[x]; i++)
                            {
                                int parent = i / (lambda[x] / mu[x]);  // note integer division
                                if (state.population.subpops.get(x).individuals.get(i).fitness.betterThan(
                                                                                                          parentPopulation.subpops.get(x).individuals.get(parent).fitness))
                                    numChildrenBetter++;
                            }
                        if (numChildrenBetter > lambda[x] / 5.0)  // note double division
                            comparison[x] = C_OVER_ONE_FIFTH_BETTER;
                        else if (numChildrenBetter < lambda[x] / 5.0)  // note double division
                            comparison[x] = C_UNDER_ONE_FIFTH_BETTER;
                        else comparison[x] = C_EXACTLY_ONE_FIFTH_BETTER;
                    }
            }
                        
        // load the parent population
        parentPopulation = state.population;
        
        // MU COMPUTATION
        
        // At this point we need to do load our population info
        // and make sure it jibes with our mu info

        // the first issue is: is the number of subpopulations
        // equal to the number of mu's?

        if (mu.length!= state.population.subpops.size()) // uh oh
            state.output.fatal("For some reason the number of subpops is different than was specified in the file (conflicting with Mu and Lambda storage).",null);

        // next, load our population, make sure there are no subpopulations smaller than the mu's
        for(int x = 0; x< state.population.subpops.size(); x++)
            {
                if (state.population.subpops.get(0).individuals.size() < mu[x])
                    state.output.error("Subpopulation " + x + " must be a multiple of the equivalent mu (that is, "+ mu[x]+").");
            }
        state.output.exitIfErrors();
        
        


        // sort evaluation to get the Mu best of each subpopulation
        
        for(int x = 0; x< state.population.subpops.size(); x++)
            {
                final ArrayList<Individual> i = state.population.subpops.get(x).individuals;

                java.util.Collections.sort(i,
                                           new java.util.Comparator<Individual>()
                                           {
                                               public int compare(Individual i1, Individual i2)
                                               {
                                                   Individual a = i1;
                                                   Individual b = i2;
                                                   // return 1 if should appear after object b in the array.
                                                   // This is the case if a has WORSE fitness.
                                                   if (b.fitness.betterThan(a.fitness)) return 1;
                                                   // return -1 if a should appear before object b in the array.
                                                   // This is the case if b has WORSE fitness.
                                                   if (a.fitness.betterThan(b.fitness)) return -1;
                                                   // else return 0
                                                   return 0;
                                               }
                                           });
            }

        // now the subpops are sorted so that the best individuals
        // appear in the lowest indexes.

        // by Ermo, it seems we no longer need setToLambda, so I am comment them out, if it works, we will delete them later
        //Population newpop = setToLambda((Population) state.population.emptyClone(),state);
        Population newpop = (Population) state.population.emptyClone();
        
        // create the count array
        count = new int[state.breedthreads];

        // divvy up the lambda individuals to create




        // how many threads do we really need?  No more than the maximum number of individuals in any subpopulation
        int numThreads = 0;
        for(int x = 0; x < state.population.subpops.size(); x++)
            numThreads = Math.max(numThreads, lambda[x]);
        numThreads = Math.min(numThreads, state.breedthreads);
        if (numThreads < state.breedthreads)
            state.output.warnOnce("Largest lambda size (" + numThreads +") is smaller than number of breedthreads (" + state.breedthreads +
                                  "), so fewer breedthreads will be created.");
            
        newIndividuals = new ArrayList[state.population.subpops.size()][numThreads];
        for(int subpop = 0; subpop < state.population.subpops.size(); subpop++)
            for(int thread = 0; thread < numThreads; thread++)
                newIndividuals[subpop][thread] = new ArrayList<Individual>();
            
        int numinds[][] = 
            new int[numThreads][state.population.subpops.size()];
        int from[][] = 
            new int[numThreads][state.population.subpops.size()];
        
        for(int x = 0; x< state.population.subpops.size(); x++)
            {
                for(int thread = 0; thread < numThreads; thread++)
                    newIndividuals[x][thread].clear();

                int length = lambda[x];

                // we will have some extra individuals.  We distribute these among the early subpopulations
                int individualsPerThread = length / numThreads;  // integer division
                int slop = length - numThreads * individualsPerThread;
                int currentFrom = 0;
                                
                for(int y=0;y<numThreads;y++)
                    {
                        if (slop > 0)
                            {
                                numinds[y][x] = individualsPerThread + 1;
                                slop--;
                            }
                        else
                            numinds[y][x] = individualsPerThread;
                    
                        if (numinds[y][x] == 0)
                            {
                                state.output.warnOnce("More threads exist than can be used to breed some subpopulations (first example: subpopulation " + x + ")");
                            }
                    
                        from[y][x] = currentFrom;
                        currentFrom += numinds[y][x];
                    }
            }

        /*

          for(int y=0;y<state.breedthreads;y++)
          for(int x=0;x<state.population.subpops.length;x++)
          {
          // figure numinds
          if (y<state.breedthreads-1) // not last one
          numinds[y][x]=
          lambda[x]/state.breedthreads;
          else // in case we're slightly off in division
          numinds[y][x]=
          lambda[x]/state.breedthreads +
          (lambda[x] - (lambda[x] / state.breedthreads)  // note integer division
          *state.breedthreads);                   
                
          // figure from
          from[y][x]=
          (lambda[x]/
          state.breedthreads) * y;
          }
        */           
        if (numThreads==1)
            {
                breedPopChunk(newpop,state,numinds[0],from[0],0);
            }
        else
            {
                Thread[] t = new Thread[numThreads];
                
                // start up the threads
                for(int y=0;y<numThreads;y++)
                    {
                        MuLambdaBreederThread r = new MuLambdaBreederThread();
                        r.threadnum = y;
                        r.newpop = newpop;
                        r.numinds = numinds[y];
                        r.from = from[y];
                        r.me = this;
                        r.state = state;
                        t[y] = new Thread(r);
                        t[y].start();
                    }
                
                // gather the threads
                for(int y=0;y<numThreads;y++) 
                    try
                        {
                            t[y].join();
                        }
                    catch(InterruptedException e)
                        {
                            state.output.fatal("Whoa! The main breeding thread got interrupted!  Dying...");
                        }
            }
            
        // Coalesce
        for(int subpop = 0; subpop < state.population.subpops.size(); subpop++)
            {
                ArrayList<Individual> newpopindividuals = newpop.subpops.get(subpop).individuals;
                for(int thread = 0; thread < numThreads; thread++)
                    {
                        newpopindividuals.addAll(newIndividuals[subpop][thread]);
                    }
            }

        return postProcess(newpop,state.population,state);
    }

    /** A hook for Mu+Lambda, not used in Mu,Lambda */

    public Population postProcess(Population newpop, Population oldpop, EvolutionState state)
    {
        return newpop;
    }
    
    
    int[] children;
    int[] parents;
    
    
    /** A private helper function for breedPopulation which breeds a chunk
        of individuals in a subpopulation for a given thread.
        Although this method is declared
        public (for the benefit of a private helper class in this file),
        you should not call it. */
    
    public void breedPopChunk(Population newpop, EvolutionState state, int[] numinds, int[] from, int threadnum) 
    {
        for(int subpop = 0; subpop< newpop.subpops.size(); subpop++)
            {
                ArrayList<Individual> putHere = (ArrayList<Individual>)newIndividuals[subpop][threadnum];

                // reset the appropriate count slot  -- this used to be outside the for-loop, a bug
                // I believe
                count[threadnum]=0;
        
                BreedingSource bp = (BreedingSource) newpop.subpops.get(subpop).
                    species.pipe_prototype.clone();
            
                // check to make sure that the breeding pipeline produces
                // the right kind of individuals.  Don't want a mistake there! :-)
                if (!bp.produces(state,newpop,subpop,threadnum))
                    state.output.fatal("The Breeding Source of subpopulation " + subpop + " does not produce individuals of the expected species " + newpop.subpops.get(subpop).species.getClass().getName() + " or fitness " + newpop.subpops.get(subpop).species.f_prototype );
                bp.prepareToProduce(state,subpop,threadnum);
                if (count[threadnum] == 0)  // the ESSelection didn't set it to nonzero to inform us of his existence
                    state.output.warnOnce("Whoa!  Breeding Source for subpop " + subpop + " doesn't have an ESSelection, but is being used by MuCommaLambdaBreeder or MuPlusLambdaBreeder.  That's probably not right.");
                // reset again
                count[threadnum] = 0;
        
                // start breedin'!
            
                int upperbound = from[subpop]+numinds[subpop];
                for(int x=from[subpop];x<upperbound;x++)
                    {
                        if (bp.produce(1,1,subpop, putHere, state,threadnum, newpop.subpops.get(subpop).species.buildMisc(state, subpop, threadnum)) != 1)
                            state.output.fatal("Whoa! Breeding Source for subpop " + subpop + " is not producing one individual at a time, as is required by the MuLambda strategies.");

                        // increment the count
                        count[threadnum]++;
                    }
                bp.finishProducing(state,subpop,threadnum);
            }
    }
}


/** A private helper class for implementing multithreaded breeding */
class MuLambdaBreederThread implements Runnable
{
    Population newpop;
    public int[] numinds;
    public int[] from;
    public MuCommaLambdaBreeder me;
    public EvolutionState state;
    public int threadnum;
    public void run()
    {
        me.breedPopChunk(newpop,state,numinds,from,threadnum);
    }
}


