package net.sf.openrocket.gui.adaptors;

import java.awt.event.ActionEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BoundedRangeModel;
import javax.swing.SpinnerModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import net.sf.openrocket.unit.Unit;
import net.sf.openrocket.unit.UnitGroup;
import net.sf.openrocket.util.ChangeSource;
import net.sf.openrocket.util.MathUtil;


/**
 * A model connector that can read and modify any value of any ChangeSource that
 * has the appropriate get/set methods defined.  
 * 
 * The variable is defined in the constructor by providing the variable name as a string
 * (e.g. "Radius" -> getRadius()/setRadius()).  Additional scaling may be applied, e.g. a 
 * DoubleModel for the diameter can be defined by the variable "Radius" and a multiplier of 2.
 * 
 * Sub-models suitable for JSpinners and other components are available from the appropriate
 * methods.
 * 
 * @author Sampo Niskanen <sampo.niskanen@iki.fi>
 */

public class DoubleModel implements ChangeListener, ChangeSource {
	private static final boolean DEBUG_LISTENERS = false;

	//////////// JSpinner Model ////////////
	
	/**
	 * Model suitable for JSpinner using JSpinner.NumberEditor.  It extends SpinnerNumberModel
	 * to be compatible with the NumberEditor, but only has the necessary methods defined.
	 */
	private class ValueSpinnerModel extends SpinnerNumberModel {
		
		@Override
		public Object getValue() {
			return currentUnit.toUnit(DoubleModel.this.getValue());
//			return makeString(currentUnit.toUnit(DoubleModel.this.getValue()));
		}

		@Override
		public void setValue(Object value) {
			
			System.out.println("setValue("+value+") called, valueName="+valueName+
					" firing="+firing);
			
			if (firing > 0)   // Ignore, if called when model is sending events
				return;
			Number num = (Number)value;
			double newValue = num.doubleValue();
			DoubleModel.this.setValue(currentUnit.fromUnit(newValue));
			
			
//			try {
//				double newValue = Double.parseDouble((String)value);
//				DoubleModel.this.setValue(currentUnit.fromUnit(newValue));
//			} catch (NumberFormatException e) { 
//				DoubleModel.this.fireStateChanged();
//			};
		}
		
		@Override
		public Object getNextValue() {
			double d = currentUnit.toUnit(DoubleModel.this.getValue());
			double max = currentUnit.toUnit(maxValue);
			if (MathUtil.equals(d,max))
				return null;
			d = currentUnit.getNextValue(d);
			if (d > max)
				d = max;
			return d;
//			return makeString(d);
		}

		@Override
		public Object getPreviousValue() {
			double d = currentUnit.toUnit(DoubleModel.this.getValue());
			double min = currentUnit.toUnit(minValue);
			if (MathUtil.equals(d,min))
				return null;
			d = currentUnit.getPreviousValue(d);
			if (d < min)
				d = min;
			return d;
//			return makeString(d);
		}

		
		@Override
		public Comparable<Double> getMinimum() {
			return currentUnit.toUnit(minValue);
		}
		
		@Override
		public Comparable<Double> getMaximum() {
			return currentUnit.toUnit(maxValue);
		}
		
		
		@Override
		public void addChangeListener(ChangeListener l) {
			DoubleModel.this.addChangeListener(l);
		}

		@Override
		public void removeChangeListener(ChangeListener l) {
			DoubleModel.this.removeChangeListener(l);
		}
	}
	
	/**
	 * Returns a new SpinnerModel with the same base as the DoubleModel.
	 * The values given to the JSpinner are in the currently selected units.
	 * 
	 * @return  A compatibility layer for a SpinnerModel.
	 */
	public SpinnerModel getSpinnerModel() {
		return new ValueSpinnerModel();
	}
	
	
	
	
	
	////////////  JSlider model  ////////////
	
	private class ValueSliderModel implements BoundedRangeModel, ChangeListener {
		private static final int MAX = 1000;
		
		/*
		 * Use linear scale  value = linear1 * x + linear0  when x < linearPosition
		 * Use quadratic scale  value = quad2 * x^2 + quad1 * x + quad0  otherwise
		 */
		
		// Linear in range x <= linearPosition
		private final double linearPosition;
		
		// May be changing DoubleModels when using linear model
		private final DoubleModel min, mid, max;
		
		// Linear multiplier and constant
		//private final double linear1;
		//private final double linear0;
		
		// Non-linear multiplier, exponent and constant
		private final double quad2,quad1,quad0;
		
		
		
		public ValueSliderModel(DoubleModel min, DoubleModel max) {
			linearPosition = 1.0;

			this.min = min;
			this.mid = max;  // Never use exponential scale
			this.max = max;
			
			min.addChangeListener(this);
			max.addChangeListener(this);

			quad2 = quad1 = quad0 = 0;  // Not used
		}
		
		
		
		/**
		 * Generate a linear model from min to max.
		 */
		public ValueSliderModel(double min, double max) {
			linearPosition = 1.0;

			this.min = new DoubleModel(min);
			this.mid = new DoubleModel(max);  // Never use exponential scale
			this.max = new DoubleModel(max);

			quad2 = quad1 = quad0 = 0;  // Not used
		}
		
		public ValueSliderModel(double min, double mid, double max) {
			this(min,0.5,mid,max);
		}
		
		/*
		 * v(x)  = mul * x^exp + add
		 * 
		 * v(pos)  = mul * pos^exp + add = mid
		 * v(1)    = mul + add = max
		 * v'(pos) = mul*exp * pos^(exp-1) = linearMul
		 */
		public ValueSliderModel(double min, double pos, double mid, double max) {
			this.min = new DoubleModel(min);
			this.mid = new DoubleModel(mid);
			this.max = new DoubleModel(max);

			
			linearPosition = pos;
			//linear0 = min;
			//linear1 = (mid-min)/pos;
			
			if (!(min < mid && mid <= max && 0 < pos && pos < 1)) {
				throw new IllegalArgumentException("Bad arguments for ValueSliderModel "+
						"min="+min+" mid="+mid+" max="+max+" pos="+pos);
			}
			
			/*
			 * quad2..0 are calculated such that
			 *   f(pos)  = mid      - continuity
			 *   f(1)    = max      - end point
			 *   f'(pos) = linear1  - continuity of derivative
			 */
			
			double delta = (mid-min)/pos;
			quad2 = (max - mid - delta + delta*pos) / pow2(pos-1);
			quad1 = (delta + 2*(mid-max)*pos - delta*pos*pos) / pow2(pos-1);
			quad0 = (mid - (2*mid+delta)*pos + (max+delta)*pos*pos) / pow2(pos-1);
			
		}
		
		private double pow2(double x) {
			return x*x;
		}
		
		public int getValue() {
			double value = DoubleModel.this.getValue();
			if (value <= min.getValue())
				return 0;
			if (value >= max.getValue())
				return MAX;
			
			double x;
			if (value <= mid.getValue()) {
				// Use linear scale
				//linear0 = min;
				//linear1 = (mid-min)/pos;
				
				x = (value - min.getValue())*linearPosition/(mid.getValue()-min.getValue());
			} else {
				// Use quadratic scale
				// Further solution of the quadratic equation
				//   a*x^2 + b*x + c-value == 0
				x = (Math.sqrt(quad1*quad1 - 4*quad2*(quad0-value)) - quad1) / (2*quad2);
			}
			return (int)(x*MAX);
		}


		public void setValue(int newValue) {
			if (firing > 0)   // Ignore loops
				return;
			
			double x = (double)newValue/MAX;
			double value;
			
			if (x <= linearPosition) {
				// Use linear scale
				//linear0 = min;
				//linear1 = (mid-min)/pos;

				value = (mid.getValue()-min.getValue())/linearPosition*x + min.getValue();
			} else {
				// Use quadratic scale
				value = quad2*x*x + quad1*x + quad0;
			}
			
			DoubleModel.this.setValue(currentUnit.fromUnit(
					currentUnit.round(currentUnit.toUnit(value))));
		}

		
		// Static get-methods
		private boolean isAdjusting;
		public int getExtent() { return 0; }
		public int getMaximum() { return MAX; }
		public int getMinimum() { return 0; }
		public boolean getValueIsAdjusting() { return isAdjusting; }
		
		// Ignore set-values
		public void setExtent(int newExtent) { }
		public void setMaximum(int newMaximum) { }
		public void setMinimum(int newMinimum) { }
		public void setValueIsAdjusting(boolean b) { isAdjusting = b; }

		public void setRangeProperties(int value, int extent, int min, int max, boolean adjusting) {
			setValueIsAdjusting(adjusting);
			setValue(value);
		}

		// Pass change listeners to the underlying model
		public void addChangeListener(ChangeListener l) {
			DoubleModel.this.addChangeListener(l);
		}

		public void removeChangeListener(ChangeListener l) {
			DoubleModel.this.removeChangeListener(l);
		}



		public void stateChanged(ChangeEvent e) {
			// Min or max range has changed.
			// Fire if not already firing
			if (firing == 0)
				fireStateChanged();
		}
	}
	
	
	public BoundedRangeModel getSliderModel(DoubleModel min, DoubleModel max) {
		return new ValueSliderModel(min,max);
	}
	
	public BoundedRangeModel getSliderModel(double min, double max) {
		return new ValueSliderModel(min,max);
	}
	
	public BoundedRangeModel getSliderModel(double min, double mid, double max) {
		return new ValueSliderModel(min,mid,max);
	}
	
	public BoundedRangeModel getSliderModel(double min, double pos, double mid, double max) {
		return new ValueSliderModel(min,pos,mid,max);
	}
	
	
	
	

	////////////  Action model  ////////////
	
	private class AutomaticActionModel extends AbstractAction implements ChangeListener {
		private boolean oldValue = false;
		
		public AutomaticActionModel() {
			oldValue = isAutomatic();
			addChangeListener(this);
		}
		

		@Override
		public boolean isEnabled() {
			// TODO: LOW: does not reflect if component is currently able to support automatic setting
			return isAutomaticAvailable();
		}
		
		@Override
		public Object getValue(String key) {
			if (key.equals(Action.SELECTED_KEY)) {
				oldValue = isAutomatic();
				return oldValue;
			}
			return super.getValue(key);
		}

		@Override
		public void putValue(String key, Object value) {
			if (firing > 0)
				return;
			if (key.equals(Action.SELECTED_KEY) && (value instanceof Boolean)) {
				oldValue = (Boolean)value;
				setAutomatic((Boolean)value);
			} else {
				super.putValue(key, value);
			}
		}

		// Implement a wrapper to the ChangeListeners
		ArrayList<PropertyChangeListener> listeners = new ArrayList<PropertyChangeListener>();
		@Override
		public void addPropertyChangeListener(PropertyChangeListener listener) {
			listeners.add(listener);
			DoubleModel.this.addChangeListener(this);
		}
		@Override
		public void removePropertyChangeListener(PropertyChangeListener listener) {
			listeners.remove(listener);
			if (listeners.isEmpty())
				DoubleModel.this.removeChangeListener(this);
		}
		// If the value has changed, generate an event to the listeners
		public void stateChanged(ChangeEvent e) {
			boolean newValue = isAutomatic();
			if (oldValue == newValue)
				return;
			PropertyChangeEvent event = new PropertyChangeEvent(this,Action.SELECTED_KEY,
					oldValue,newValue);
			oldValue = newValue;
			Object[] l = listeners.toArray();
			for (int i=0; i<l.length; i++) {
				((PropertyChangeListener)l[i]).propertyChange(event);
			}
		}

		public void actionPerformed(ActionEvent e) {
			// Setting performed in putValue
		}

	}
	
	/**
	 * Returns a new Action corresponding to the changes of the automatic setting
	 * property of the value model.  This may be used directly with e.g. check buttons.
	 * 
	 * @return  A compatibility layer for an Action.
	 */
	public Action getAutomaticAction() {
		return new AutomaticActionModel();
	}
	
	
	
	


	////////////  Main model  /////////////

	/*
	 * The main model handles all values in SI units, i.e. no conversion is made within the model.
	 */
	
	private final ChangeSource source;
	private final String valueName;
	private final double multiplier;
	
	private final Method getMethod;
	private final Method setMethod;
	
	private final Method getAutoMethod;
	private final Method setAutoMethod;
	
	private final ArrayList<ChangeListener> listeners = new ArrayList<ChangeListener>();
	
	private final UnitGroup units;
	private Unit currentUnit;

	private final double minValue;
	private final double maxValue;

	
	private int firing = 0;  //  >0 when model itself is sending events
	
	
	// Used to differentiate changes in valueName and other changes in the component:
	private double lastValue = 0;
	private boolean lastAutomatic = false;
		
	
	public DoubleModel(double value) {
		this(value, UnitGroup.UNITS_NONE,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY);
	}
	
	public DoubleModel(double value, UnitGroup unit) {
		this(value,unit,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY);
	}
	
	public DoubleModel(double value, UnitGroup unit, double min) {
		this(value,unit,min,Double.POSITIVE_INFINITY);
	}
	
	public DoubleModel(double value, UnitGroup unit, double min, double max) {
		this.lastValue = value;
		this.minValue = min;
		this.maxValue = max;

		source = null;
		valueName = "Constant value";
		multiplier = 1;
		
		getMethod = setMethod = null;
		getAutoMethod = setAutoMethod = null;
		units = unit;
		currentUnit = units.getDefaultUnit();
	}

	
	/**
	 * Generates a new DoubleModel that changes the values of the specified component.
	 * The double value is read and written using the methods "get"/"set" + valueName.
	 *  
	 * @param source Component whose parameter to use.
	 * @param valueName Name of metods used to get/set the parameter.
	 * @param multiplier Value shown by the model is the value from component.getXXX * multiplier
	 * @param min Minimum value allowed (in SI units)
	 * @param max Maximum value allowed (in SI units)
	 */
	public DoubleModel(ChangeSource source, String valueName, double multiplier, UnitGroup unit,
			double min, double max) {
		this.source = source;
		this.valueName = valueName;
		this.multiplier = multiplier;

		this.units = unit;
		currentUnit = units.getDefaultUnit();
		
		this.minValue = min;
		this.maxValue = max;
		
		try {
			getMethod = source.getClass().getMethod("get" + valueName);
		} catch (NoSuchMethodException e) {
			throw new IllegalArgumentException("get method for value '"+valueName+
					"' not present in class "+source.getClass().getCanonicalName());
		}

		Method s=null;
		try {
			s = source.getClass().getMethod("set" + valueName,double.class);
		} catch (NoSuchMethodException e1) { }  // Ignore
		setMethod = s;
		
		// Automatic selection methods
		
		Method set=null,get=null;
		
		try {
			get = source.getClass().getMethod("is" + valueName + "Automatic");
			set = source.getClass().getMethod("set" + valueName + "Automatic",boolean.class);
		} catch (NoSuchMethodException e) { } // ignore
		
		if (set!=null && get!=null) {
			getAutoMethod = get;
			setAutoMethod = set;
		} else {
			getAutoMethod = null;
			setAutoMethod = null;
		}
		
	}

	public DoubleModel(ChangeSource source, String valueName, double multiplier, UnitGroup unit,
			double min) {
		this(source,valueName,multiplier,unit,min,Double.POSITIVE_INFINITY);
	}
	
	public DoubleModel(ChangeSource source, String valueName, double multiplier, UnitGroup unit) {
		this(source,valueName,multiplier,unit,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY);
	}
	
	public DoubleModel(ChangeSource source, String valueName, UnitGroup unit, 
			double min, double max) {
		this(source,valueName,1.0,unit,min,max);
	}
	
	public DoubleModel(ChangeSource source, String valueName, UnitGroup unit, double min) {
		this(source,valueName,1.0,unit,min,Double.POSITIVE_INFINITY);
	}
	
	public DoubleModel(ChangeSource source, String valueName, UnitGroup unit) {
		this(source,valueName,1.0,unit,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY);
	}

	public DoubleModel(ChangeSource source, String valueName) {
		this(source,valueName,1.0,UnitGroup.UNITS_NONE,
				Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY);
	}

	public DoubleModel(ChangeSource source, String valueName, double min) {
		this(source,valueName,1.0,UnitGroup.UNITS_NONE,min,Double.POSITIVE_INFINITY);
	}
	
	public DoubleModel(ChangeSource source, String valueName, double min, double max) {
		this(source,valueName,1.0,UnitGroup.UNITS_NONE,min,max);
	}
	
	
	
	/**
	 * Returns the value of the variable (in SI units).
	 */
	public double getValue() {
		if (getMethod==null)  // Constant value
			return lastValue;

		try {
			return (Double)getMethod.invoke(source)*multiplier;
		} catch (IllegalArgumentException e) {
			throw new RuntimeException("BUG: Unable to invoke getMethod of "+this, e);
		} catch (IllegalAccessException e) {
			throw new RuntimeException("BUG: Unable to invoke getMethod of "+this, e);
		} catch (InvocationTargetException e) {
			throw new RuntimeException("BUG: Unable to invoke getMethod of "+this, e);
		}
	}
	
	/**
	 * Sets the value of the variable.
	 * @param v New value for parameter in SI units.
	 */
	public void setValue(double v) {
		if (setMethod==null) {
			if (getMethod != null) {
				throw new RuntimeException("setMethod not available for variable '"+valueName+
						"' in class "+source.getClass().getCanonicalName());
			}
			lastValue = v;
			fireStateChanged();
			return;
		}

		try {
			setMethod.invoke(source, v/multiplier);
			return;
		} catch (IllegalArgumentException e) {
			throw new RuntimeException("BUG: Unable to invoke setMethod of "+this, e);
		} catch (IllegalAccessException e) {
			throw new RuntimeException("BUG: Unable to invoke setMethod of "+this, e);
		} catch (InvocationTargetException e) {
			throw new RuntimeException("BUG: Unable to invoke setMethod of "+this, e);
		}
	}

	
	/**
	 * Returns whether setting the value automatically is available.
	 */
	public boolean isAutomaticAvailable() {
		return (getAutoMethod != null) && (setAutoMethod != null);
	}

	/**
	 * Returns whether the value is currently being set automatically.
	 * Returns false if automatic setting is not available at all.
	 */
	public boolean isAutomatic() {
		if (getAutoMethod == null)
			return false;
		
		try {
			return (Boolean)getAutoMethod.invoke(source);
		} catch (IllegalArgumentException e) {
			e.printStackTrace();
		} catch (IllegalAccessException e) {
			e.printStackTrace();
		} catch (InvocationTargetException e) {
			e.printStackTrace();
		}
		return false;  // Should not occur
	}
	
	/**
	 * Sets whether the value should be set automatically.  Simply fires a
	 * state change event if automatic setting is not available.
	 */
	public void setAutomatic(boolean auto) {
		if (setAutoMethod == null) {
			fireStateChanged();  // in case something is out-of-sync
			return;
		}
		
		try {
			lastAutomatic = auto;
			setAutoMethod.invoke(source, auto);
			return;
		} catch (IllegalArgumentException e) {
			e.printStackTrace();
		} catch (IllegalAccessException e) {
			e.printStackTrace();
		} catch (InvocationTargetException e) {
			e.printStackTrace();
		}
		fireStateChanged();  // Should not occur
	}
	

	/**
	 * Returns the current Unit.  At the beginning it is the default unit of the UnitGroup.
	 * @return The most recently set unit.
	 */
	public Unit getCurrentUnit() {
		return currentUnit;
	}
	
	/**
	 * Sets the current Unit.  The unit must be one of those included in the UnitGroup.
	 * @param u  The unit to set active.
	 */
	public void setCurrentUnit(Unit u) {
		if (currentUnit == u)
			return;
		currentUnit = u;
		fireStateChanged();
	}
	
	
	/**
	 * Returns the UnitGroup associated with the parameter value.
	 *
	 * @return The UnitGroup given to the constructor.
	 */
	public UnitGroup getUnitGroup() {
		return units;
	}
	
	
	
	/**
	 * Add a listener to the model.  Adds the model as a listener to the Component if this
	 * is the first listener.
	 * @param l Listener to add.
	 */
	public void addChangeListener(ChangeListener l) {
		if (listeners.isEmpty()) {
			if (source != null) {
				source.addChangeListener(this);
				lastValue = getValue();
				lastAutomatic = isAutomatic();
			}
		}

		listeners.add(l);
		if (DEBUG_LISTENERS)
			System.out.println(this+" adding listener (total "+listeners.size()+"): "+l);
	}

	/**
	 * Remove a listener from the model.  Removes the model from being a listener to the Component
	 * if this was the last listener of the model.
	 * @param l Listener to remove.
	 */
	public void removeChangeListener(ChangeListener l) {
		listeners.remove(l);
		if (listeners.isEmpty() && source != null) {
			source.removeChangeListener(this);
		}
		if (DEBUG_LISTENERS)
			System.out.println(this+" removing listener (total "+listeners.size()+"): "+l);
	}
	
	/**
	 * Fire a ChangeEvent to all listeners.
	 */
	protected void fireStateChanged() {
		Object[] l = listeners.toArray();
		ChangeEvent event = new ChangeEvent(this);
		firing++;
		for (int i=0; i<l.length; i++)
			((ChangeListener)l[i]).stateChanged(event);
		firing--;
	}

	/**
	 * Called when the component changes.  Checks whether the modeled value has changed, and if
	 * it has, updates lastValue and generates ChangeEvents for all listeners of the model.
	 */
	public void stateChanged(ChangeEvent e) {
		double v = getValue();
		boolean b = isAutomatic();
		if (lastValue == v && lastAutomatic == b)
			return;
		lastValue = v;
		lastAutomatic = b;
		fireStateChanged();
	}

	/**
	 * Explain the DoubleModel as a String.
	 */
	@Override
	public String toString() {
		if (source == null)
			return "DoubleModel[constant="+lastValue+"]";
		return "DoubleModel["+source.getClass().getCanonicalName()+":"+valueName+"]";
	}
}